import Foundation
import AVFoundation
import PrismShared

extension AppleHost {
    func microphonePermission(completion: @escaping (KotlinBoolean) -> KotlinUnit) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in DispatchQueue.main.async { _ = completion(KotlinBoolean(value: granted)) } }
    }
    func recordPcm(sampleRate: Int32, samples: @escaping (Data) -> KotlinUnit, failure: @escaping (String) -> KotlinUnit) -> any IosCancellation {
        let recording = MicrophoneRecording(rate: Double(sampleRate), samples: samples, failure: failure)
        DispatchQueue.main.async { recording.start() }
        return AppleCancellation { DispatchQueue.main.async { recording.stop() } }
    }
    func playPcm(data: Data, sampleRate: Int32, completion: @escaping (String?) -> KotlinUnit) -> any IosCancellation {
        let playback = AudioPlayback(data: data, rate: Double(sampleRate), completion: completion)
        DispatchQueue.main.async { playback.start() }
        return AppleCancellation { DispatchQueue.main.async { playback.stop() } }
    }
}
private func configureSession() throws {
    let session = AVAudioSession.sharedInstance()
    try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
    try session.setActive(true)
}
private final class MicrophoneRecording {
    let engine = AVAudioEngine(), rate: Double
    let samples: (Data) -> KotlinUnit, failure: (String) -> KotlinUnit
    var cancelled = false, started = false
    init(rate: Double, samples: @escaping (Data) -> KotlinUnit, failure: @escaping (String) -> KotlinUnit) { self.rate = rate; self.samples = samples; self.failure = failure }
    func start() {
        guard !cancelled else { return }
        do {
            try configureSession()
            let input = engine.inputNode, source = input.outputFormat(forBus: 0)
            guard source.sampleRate > 0, let target = AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: rate, channels: 1, interleaved: false), let converter = AVAudioConverter(from: source, to: target) else { throw AudioFailure.unavailable }
            input.installTap(onBus: 0, bufferSize: 4096, format: source) { [weak self] buffer, _ in
                guard let self, !self.cancelled else { return }
                let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * self.rate / source.sampleRate) + 32)
                guard let converted = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
                var supplied = false, error: NSError?
                let status = converter.convert(to: converted, error: &error) { _, outStatus in
                    if supplied { outStatus.pointee = .noDataNow; return nil }
                    supplied = true; outStatus.pointee = .haveData; return buffer
                }
                if status == .error { _ = self.failure(error?.localizedDescription ?? "Microphone conversion failed"); return }
                if let channel = converted.int16ChannelData?[0], converted.frameLength > 0 { _ = self.samples(Data(bytes: channel, count: Int(converted.frameLength) * 2)) }
            }
            started = true; try engine.start()
        } catch { stop(); _ = failure(error.localizedDescription) }
    }
    func stop() { cancelled = true; if started { engine.inputNode.removeTap(onBus: 0); engine.stop(); started = false } }
    deinit { engine.stop() }
}
private final class AudioPlayback {
    let engine = AVAudioEngine(), player = AVAudioPlayerNode(), data: Data, rate: Double
    let completion: (String?) -> KotlinUnit
    var cancelled = false
    init(data: Data, rate: Double, completion: @escaping (String?) -> KotlinUnit) { self.data = data; self.rate = rate; self.completion = completion }
    func start() {
        guard !cancelled else { return }
        do {
            try configureSession()
            guard let format = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1, interleaved: false), let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(data.count / 2)), let out = buffer.floatChannelData?[0] else { throw AudioFailure.unavailable }
            buffer.frameLength = buffer.frameCapacity
            data.withUnsafeBytes { (bytes: UnsafeRawBufferPointer) in
                for i in 0..<Int(buffer.frameLength) { let value = UInt16(bytes[i * 2]) | (UInt16(bytes[i * 2 + 1]) << 8); out[i] = Float(Int16(bitPattern: value)) / 32768 }
            }
            engine.attach(player); engine.connect(player, to: engine.mainMixerNode, format: format)
            try engine.start()
            player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [self] _ in
                DispatchQueue.main.async { if !cancelled { stop(); _ = completion(nil) } }
            }
            player.play()
        } catch { stop(); _ = completion(error.localizedDescription) }
    }
    func stop() { cancelled = true; player.stop(); engine.stop() }
}
private enum AudioFailure: Error { case unavailable }
