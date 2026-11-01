import AVFoundation

/// Microphone capture -> Opus packets, and Opus packets -> speaker.
/// The engine only runs while someone is talking or a clip is playing,
/// so it costs nothing when the app is idle.
final class AudioEngine {
    /// Called with batches of up to 3 packets (60 ms) while capturing.
    var onPackets: (([Data]) -> Void)?
    /// Microphone level 0...1 for the waveform, ~every 60 ms.
    var onLevel: ((Float) -> Void)?
    var speakerMuted = false
    /// Diagnostics (sent to the server log).
    var onDiag: ((String) -> Void)?
    private(set) var isCapturing = false
    private var stats = (frames: 0, packets: 0, failed: 0)

    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let codec: OpusCodec?
    private let workQ = DispatchQueue(label: "walkie.audio")

    private var capturing = false
    private var converter: AVAudioConverter?
    private var pending: AVAudioPCMBuffer?
    private var batch: [Data] = []
    private var queuedFrames = 0
    private var jitterHeld: [AVAudioPCMBuffer] = []
    private var playing = false

    init() {
        codec = try? OpusCodec()
        engine.attach(player)
        if let fmt = codec?.pcmFormat {
            engine.connect(player, to: engine.mainMixerNode, format: fmt)
        }
        NotificationCenter.default.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] _ in
            self?.stopCapture()
        }
    }

    var codecAvailable: Bool { codec != nil }

    /// Call once at launch. The PushToTalk framework activates/deactivates
    /// the session itself; we only set the category.
    static func configureSession() {
        let s = AVAudioSession.sharedInstance()
        try? s.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP])
    }

    /// Used for replaying history when PushToTalk is not managing the session.
    static func activateForPlayback() {
        configureSession()
        try? AVAudioSession.sharedInstance().setActive(true)
    }

    // MARK: engine lifecycle

    private func startEngineIfNeeded() {
        guard !engine.isRunning else { return }
        engine.prepare()
        do { try engine.start() } catch { print("audio engine start failed: \(error)") }
    }

    func sessionActivated() {
        startEngineIfNeeded()
    }

    func sessionDeactivated() {
        stopCapture()
        player.stop()
        playing = false
        engine.stop()
    }

    // MARK: capture

    func startCapture() {
        guard let codec, !capturing else { return }
        let input = engine.inputNode
        // No voice processing: it lowers playback volume, and half-duplex
        // talk (one speaker at a time) doesn't need echo cancellation.
        let hw = input.outputFormat(forBus: 0)
        guard hw.sampleRate > 0, hw.channelCount > 0 else {
            onDiag?("capture: microphone has no format yet (audio session not active)")
            return
        }
        converter = AVAudioConverter(from: hw, to: codec.pcmFormat)
        stats = (0, 0, 0)
        pending = AVAudioPCMBuffer(pcmFormat: codec.pcmFormat, frameCapacity: OpusCodec.frameSize)
        pending?.frameLength = 0
        batch.removeAll()
        input.removeTap(onBus: 0)
        input.installTap(onBus: 0, bufferSize: 2048, format: hw) { [weak self] buf, _ in
            self?.workQ.async { self?.ingest(buf) }
        }
        capturing = true
        isCapturing = true
        startEngineIfNeeded()
        onDiag?("capture started: \(Int(hw.sampleRate)) Hz, \(hw.channelCount) ch, engine running: \(engine.isRunning)")
    }

    func stopCapture() {
        guard capturing else { return }
        capturing = false
        isCapturing = false
        engine.inputNode.removeTap(onBus: 0)
        workQ.async { [weak self] in
            guard let self else { return }
            self.onDiag?("capture stopped: \(self.stats.frames) mic buffers, \(self.stats.packets) packets encoded, \(self.stats.failed) encode failures")
            if !self.batch.isEmpty { self.onPackets?(self.batch) }
            self.batch.removeAll()
            self.pending = nil
        }
    }

    private func ingest(_ buf: AVAudioPCMBuffer) {
        guard capturing, let codec, let converter else { return }
        stats.frames += 1
        let ratio = codec.pcmFormat.sampleRate / buf.format.sampleRate
        let cap = AVAudioFrameCount(Double(buf.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: codec.pcmFormat, frameCapacity: cap) else { return }
        var given = false
        var err: NSError?
        converter.convert(to: out, error: &err) { _, st in
            if given { st.pointee = .noDataNow; return nil }
            given = true
            st.pointee = .haveData
            return buf
        }
        guard out.frameLength > 0, let src = out.floatChannelData?[0] else { return }

        var level: Float = 0
        var i = 0
        let n = Int(out.frameLength)
        while i < n {
            guard let pending, let dst = pending.floatChannelData?[0] else { return }
            let room = Int(OpusCodec.frameSize - pending.frameLength)
            let take = min(room, n - i)
            for k in 0..<take {
                let v = src[i + k]
                dst[Int(pending.frameLength) + k] = v
                level = max(level, abs(v))
            }
            pending.frameLength += AVAudioFrameCount(take)
            i += take
            if pending.frameLength == OpusCodec.frameSize {
                if let pkt = codec.encode(pending) {
                    batch.append(pkt)
                    stats.packets += 1
                } else {
                    stats.failed += 1
                    if stats.failed == 1 { onDiag?("encode failed: \(codec.lastError ?? "no packet produced")") }
                }
                pending.frameLength = 0
                if batch.count >= 3 {
                    onPackets?(batch)
                    batch.removeAll()
                    onLevel?(min(1, level * 2))
                    level = 0
                }
            }
        }
    }

    // MARK: playback

    /// Plays live packets with a ~60 ms jitter buffer.
    func play(packets: [Data]) {
        guard let codec else { return }
        workQ.async { [weak self] in
            guard let self else { return }
            for p in packets {
                guard let pcm = codec.decode(p) else { continue }
                if self.speakerMuted { continue }
                if !self.playing {
                    self.jitterHeld.append(pcm)
                    if self.jitterHeld.count >= 3 { self.flushJitter() }
                } else {
                    self.schedule(pcm)
                }
            }
        }
    }

    /// Call when a transmission ends so a short message still plays.
    func endOfStream() {
        workQ.async { [weak self] in self?.flushJitter() ; self?.playing = false }
    }

    private func flushJitter() {
        guard !jitterHeld.isEmpty else { return }
        startEngineIfNeeded()
        for b in jitterHeld { schedule(b) }
        jitterHeld.removeAll()
        if !player.isPlaying { player.play() }
        playing = true
    }

    private func schedule(_ b: AVAudioPCMBuffer) {
        player.scheduleBuffer(b, completionHandler: nil)
    }

    /// Plays a whole clip file (format "WKC1", see docs/PROTOCOL.md).
    /// `completion` runs on the main queue when playback finishes.
    func playClip(_ file: Data, progress: ((Double) -> Void)? = nil, completion: @escaping () -> Void) {
        guard let codec else { completion(); return }
        workQ.async { [weak self] in
            guard let self else { return }
            let packets = AudioEngine.parseClip(file)
            let bufs = packets.compactMap { codec.decode($0) }
            guard !bufs.isEmpty else { DispatchQueue.main.async(execute: completion); return }
            self.startEngineIfNeeded()
            self.player.stop()
            let total = Double(bufs.count)
            for (i, b) in bufs.enumerated() {
                let last = i == bufs.count - 1
                self.player.scheduleBuffer(b) {
                    DispatchQueue.main.async {
                        progress?(Double(i + 1) / total)
                        if last { completion() }
                    }
                }
            }
            self.player.play()
        }
    }

    func stopPlayback() {
        workQ.async { [weak self] in
            self?.player.stop()
            self?.jitterHeld.removeAll()
            self?.playing = false
        }
    }

    static func parseClip(_ d: Data) -> [Data] {
        let b = [UInt8](d)
        guard b.count >= 10, String(bytes: b[0..<4], encoding: .ascii) == "WKC1" else { return [] }
        var out: [Data] = []
        var i = 10
        while i + 2 <= b.count {
            let len = Int(b[i]) << 8 | Int(b[i + 1])
            i += 2
            guard len > 0, i + len <= b.count else { break }
            out.append(Data(b[i..<i + len]))
            i += len
        }
        return out
    }
}
