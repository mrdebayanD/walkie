import CoreLocation

/// Location is read only during an SOS (battery and privacy).
@MainActor
final class Location: NSObject, CLLocationManagerDelegate {
    static let shared = Location()
    private let manager = CLLocationManager()
    private var waiters: [CheckedContinuation<CLLocation?, Never>] = []
    var onUpdate: ((CLLocation) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        manager.distanceFilter = 25
    }

    func requestPermission() {
        if manager.authorizationStatus == .notDetermined {
            manager.requestWhenInUseAuthorization()
        }
    }

    var authorized: Bool {
        [.authorizedWhenInUse, .authorizedAlways].contains(manager.authorizationStatus)
    }

    /// One fix, or nil after `timeout` seconds.
    func current(timeout: TimeInterval = 6) async -> CLLocation? {
        if !authorized { requestPermission() }
        if let last = manager.location, -last.timestamp.timeIntervalSinceNow < 30 { return last }
        return await withCheckedContinuation { c in
            waiters.append(c)
            manager.requestLocation()
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
                self.resolve(self.manager.location)
            }
        }
    }

    func startTracking() { manager.startUpdatingLocation() }
    func stopTracking() { manager.stopUpdatingLocation() }

    private func resolve(_ loc: CLLocation?) {
        let w = waiters
        waiters.removeAll()
        w.forEach { $0.resume(returning: loc) }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last else { return }
        Task { @MainActor in
            self.resolve(l)
            self.onUpdate?(l)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in self.resolve(nil) }
    }
}
