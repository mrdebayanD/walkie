import AVFoundation

/// Opus mono 48 kHz, 20 ms frames, using Apple's built-in Opus codec
/// (AudioToolbox, iOS 11+). Matches the Android side (Concentus) bit-for-bit
/// at the packet level, so either can decode the other.
final class OpusCodec {
    static let sampleRate: Double = 48_000
    static let frameSize: AVAudioFrameCount = 960 // 20 ms
    static let bitrate = 16_000

    let pcmFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: OpusCodec.sampleRate, channels: 1, interleaved: false)!
    let opusFormat: AVAudioFormat

    private let encoder: AVAudioConverter
    private let decoder: AVAudioConverter

    init() throws {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: OpusCodec.sampleRate, mFormatID: kAudioFormatOpus, mFormatFlags: 0,
            mBytesPerPacket: 0, mFramesPerPacket: OpusCodec.frameSize, mBytesPerFrame: 0,
            mChannelsPerFrame: 1, mBitsPerChannel: 0, mReserved: 0)
        guard let fmt = AVAudioFormat(streamDescription: &asbd) else { throw CodecError.unsupported }
        opusFormat = fmt
        guard let enc = AVAudioConverter(from: pcmFormat, to: opusFormat),
              let dec = AVAudioConverter(from: opusFormat, to: pcmFormat) else { throw CodecError.unsupported }
        enc.bitRate = OpusCodec.bitrate
        encoder = enc
        decoder = dec
    }

    enum CodecError: Error { case unsupported, failed }

    /// Encodes exactly one 960-frame buffer into one Opus packet.
    func encode(_ pcm: AVAudioPCMBuffer) -> Data? {
        let out = AVAudioCompressedBuffer(format: opusFormat, packetCapacity: 1, maximumPacketSize: 1275)
        var fed = false
        var err: NSError?
        let status = encoder.convert(to: out, error: &err) { _, inStatus in
            if fed {
                inStatus.pointee = .noDataNow
                return nil
            }
            fed = true
            inStatus.pointee = .haveData
            return pcm
        }
        guard status != .error, out.packetCount > 0, let desc = out.packetDescriptions?.pointee else { return nil }
        return Data(bytes: out.data.advanced(by: Int(desc.mStartOffset)), count: Int(desc.mDataByteSize))
    }

    /// Decodes one Opus packet into PCM (normally 960 frames).
    func decode(_ packet: Data) -> AVAudioPCMBuffer? {
        guard !packet.isEmpty, packet.count <= 1275 else { return nil }
        let input = AVAudioCompressedBuffer(format: opusFormat, packetCapacity: 1, maximumPacketSize: packet.count)
        packet.withUnsafeBytes { raw in
            input.data.copyMemory(from: raw.baseAddress!, byteCount: packet.count)
        }
        input.byteLength = UInt32(packet.count)
        input.packetCount = 1
        input.packetDescriptions?.pointee = AudioStreamPacketDescription(mStartOffset: 0, mVariableFramesInPacket: 0, mDataByteSize: UInt32(packet.count))
        // Opus packets can hold up to 120 ms; size for the worst case.
        guard let out = AVAudioPCMBuffer(pcmFormat: pcmFormat, frameCapacity: 5760) else { return nil }
        var fed = false
        var err: NSError?
        let status = decoder.convert(to: out, error: &err) { _, inStatus in
            if fed {
                inStatus.pointee = .noDataNow
                return nil
            }
            fed = true
            inStatus.pointee = .haveData
            return input
        }
        guard status != .error, out.frameLength > 0 else { return nil }
        return out
    }
}
