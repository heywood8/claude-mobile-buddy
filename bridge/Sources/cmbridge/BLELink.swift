import Foundation
import CoreBluetooth

/// The central half of the link: finds the phone, keeps a connection to it, and moves
/// newline-delimited lines in both directions.
///
/// All CoreBluetooth work happens on a private serial queue. Callers touch only `isLinked`
/// and `send(_:)`, which are safe from anywhere.
/// `@unchecked Sendable`: every mutable field is touched only on `queue`, and `isLinked` is
/// guarded by `lock`. The compiler cannot see either invariant.
final class BLELink: NSObject, LinkSink, @unchecked Sendable {
    private let queue = DispatchQueue(label: "cmbridge.ble")
    private let log: Logger

    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var rx: CBCharacteristic?
    private var assembler = LineAssembler()

    /// Chunks waiting on the previous write to be acknowledged.
    private var pendingWrites: [Data] = []
    private var writeInFlight = false

    private let lock = NSLock()
    private var _isLinked = false

    /// Called on the BLE queue with each complete line received from the phone.
    var onLine: ((Data) -> Void)?
    /// Called on the BLE queue whenever the link comes up or goes down.
    var onLinkChange: ((Bool) -> Void)?

    var isLinked: Bool {
        lock.lock(); defer { lock.unlock() }
        return _isLinked
    }

    init(log: Logger) {
        self.log = log
        super.init()
        central = CBCentralManager(delegate: self, queue: queue)
    }

    func send(_ line: Data) {
        queue.async { [weak self] in
            guard let self, let peripheral = self.peripheral, let rx = self.rx else { return }
            let limit = max(20, peripheral.maximumWriteValueLength(for: .withResponse))
            var offset = line.startIndex
            while offset < line.endIndex {
                let end = line.index(offset, offsetBy: limit, limitedBy: line.endIndex) ?? line.endIndex
                self.pendingWrites.append(Data(line[offset..<end]))
                offset = end
            }
            _ = rx
            self.pumpWrites()
        }
    }

    private func pumpWrites() {
        guard !writeInFlight,
              let peripheral, let rx,
              !pendingWrites.isEmpty else { return }
        let chunk = pendingWrites.removeFirst()
        writeInFlight = true
        peripheral.writeValue(chunk, for: rx, type: .withResponse)
    }

    private func setLinked(_ value: Bool) {
        lock.lock()
        let changed = _isLinked != value
        _isLinked = value
        lock.unlock()
        if changed { onLinkChange?(value) }
    }

    private func startScan() {
        guard central.state == .poweredOn else { return }
        log.info("scanning for \(NUS.service)")
        central.scanForPeripherals(withServices: [CBUUID(string: NUS.service)])
    }

    private func teardown(_ reason: String) {
        log.info("link down: \(reason)")
        pendingWrites.removeAll()
        writeInFlight = false
        assembler = LineAssembler()
        rx = nil
        peripheral = nil
        setLinked(false)
        startScan()
    }
}

extension BLELink: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            startScan()
        case .unauthorized:
            // A command-line binary inherits Bluetooth permission from whatever launched it.
            // Under launchd there is nothing to inherit from, which is why the bridge ships
            // as an .app bundle with a usage description. See README.
            log.error("bluetooth not authorised for this process")
        case .poweredOff:
            log.error("bluetooth is off")
        default:
            log.info("bluetooth state: \(central.state.rawValue)")
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {
        guard self.peripheral == nil else { return }
        let name = peripheral.name ?? (advertisementData[CBAdvertisementDataLocalNameKey] as? String) ?? "?"
        log.info("found \(name) rssi \(RSSI)")
        self.peripheral = peripheral
        peripheral.delegate = self
        central.stopScan()
        central.connect(peripheral)
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        log.info("connected, discovering services")
        peripheral.discoverServices([CBUUID(string: NUS.service)])
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        teardown("connect failed: \(error?.localizedDescription ?? "unknown")")
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        teardown(error?.localizedDescription ?? "peer closed")
    }
}

extension BLELink: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let service = peripheral.services?.first(where: {
            $0.uuid == CBUUID(string: NUS.service)
        }) else {
            teardown("no NUS service")
            return
        }
        peripheral.discoverCharacteristics(
            [CBUUID(string: NUS.rx), CBUUID(string: NUS.tx)], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid {
            case CBUUID(string: NUS.rx):
                rx = characteristic
            case CBUUID(string: NUS.tx):
                peripheral.setNotifyValue(true, for: characteristic)
            default:
                break
            }
        }
        guard rx != nil else {
            teardown("no RX characteristic")
            return
        }
        log.info("link up, mtu \(peripheral.maximumWriteValueLength(for: .withResponse))")
        setLinked(true)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard let value = characteristic.value else { return }
        for line in assembler.feed(value) {
            onLine?(line)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        if let error { log.error("write failed: \(error.localizedDescription)") }
        writeInFlight = false
        pumpWrites()
    }
}
