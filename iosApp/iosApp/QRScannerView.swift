import AVFoundation
import SwiftUI
import UIKit

struct QRScannerView: View {
    var onCode: (String) -> Void
    var onUnavailable: () -> Void
    var onCancel: () -> Void

    var body: some View {
        ZStack {
            QRScannerRepresentable(onCode: onCode, onUnavailable: onUnavailable)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .ignoresSafeArea()
            scanChrome
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color.black.ignoresSafeArea())
        .presentationBackground(Color.black)
        .preferredColorScheme(.dark)
    }

    private var scanChrome: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onCancel) {
                    HStack(spacing: 6) {
                        Image(systemName: "xmark")
                            .font(.system(size: 15, weight: .semibold))
                        Text("取消")
                            .font(.system(size: 17, weight: .semibold))
                    }
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .frame(height: 44)
                    .background(.ultraThinMaterial, in: Capsule())
                    .overlay {
                        Capsule().stroke(Color.white.opacity(0.35), lineWidth: 0.6)
                    }
                }
                .buttonStyle(.plain)
                .accessibilityLabel("取消")
                Spacer()
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
            Spacer(minLength: 0)
            CornerBrackets()
                .stroke(
                    Color.white,
                    style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                )
                .shadow(color: .black.opacity(0.55), radius: 3, y: 1)
                .frame(width: 248, height: 248)
                .accessibilityHidden(true)
            Text("对准电脑上的码")
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.55), radius: 6, y: 1)
                .padding(.top, 22)
                .accessibilityAddTraits(.isStaticText)
            Spacer(minLength: 0)
            Spacer(minLength: 0)
        }
    }
}

private struct CornerBrackets: Shape {
    var arm: CGFloat = 36
    var radius: CGFloat = 18

    func path(in rect: CGRect) -> Path {
        var path = Path()
        let r = min(radius, arm, rect.width / 2, rect.height / 2)
        path.move(to: CGPoint(x: rect.minX, y: rect.minY + arm))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY + r))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX + r, y: rect.minY),
            control: CGPoint(x: rect.minX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.minX + arm, y: rect.minY))

        path.move(to: CGPoint(x: rect.maxX - arm, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX - r, y: rect.minY))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX, y: rect.minY + r),
            control: CGPoint(x: rect.maxX, y: rect.minY)
        )
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY + arm))

        path.move(to: CGPoint(x: rect.maxX, y: rect.maxY - arm))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY - r))
        path.addQuadCurve(
            to: CGPoint(x: rect.maxX - r, y: rect.maxY),
            control: CGPoint(x: rect.maxX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.maxX - arm, y: rect.maxY))

        path.move(to: CGPoint(x: rect.minX + arm, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX + r, y: rect.maxY))
        path.addQuadCurve(
            to: CGPoint(x: rect.minX, y: rect.maxY - r),
            control: CGPoint(x: rect.minX, y: rect.maxY)
        )
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY - arm))
        return path
    }
}

private struct QRScannerRepresentable: UIViewControllerRepresentable {
    var onCode: (String) -> Void
    var onUnavailable: () -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController()
        controller.onCode = onCode
        controller.onUnavailable = onUnavailable
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {
        uiViewController.onCode = onCode
        uiViewController.onUnavailable = onUnavailable
    }

    static func dismantleUIViewController(_ uiViewController: QRScannerViewController, coordinator: ()) {
        uiViewController.tearDown()
    }
}

final class QRScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    var onUnavailable: (() -> Void)?

    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "tech.asahiart.luvia.qr")
    private var camera: AVCaptureDevice?
    private var didEmit = false
    private var configured = false
    private var appeared = false

    override func loadView() {
        let host = CameraPreviewView()
        host.videoLayer.session = session
        host.videoLayer.videoGravity = .resizeAspectFill
        host.backgroundColor = .black
        view = host
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        authorize()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        updateOrientation()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        appeared = true
        updateOrientation()
        startIfNeeded()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        appeared = false
        tearDown()
    }

    func tearDown() {
        sessionQueue.async { [weak self] in
            guard let self, self.session.isRunning else { return }
            self.session.stopRunning()
        }
    }

    private func authorize() {
        guard AVCaptureDevice.default(for: .video) != nil else {
            reportUnavailable()
            return
        }
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    if granted {
                        self?.configureSession()
                    } else {
                        self?.reportUnavailable()
                    }
                }
            }
        case .denied, .restricted:
            reportUnavailable()
        @unknown default:
            reportUnavailable()
        }
    }

    private func configureSession() {
        sessionQueue.async { [weak self] in
            guard let self, !self.configured else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = .high

            guard
                let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back)
                    ?? AVCaptureDevice.default(for: .video),
                let input = try? AVCaptureDeviceInput(device: device),
                self.session.canAddInput(input)
            else {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.reportUnavailable() }
                return
            }
            self.session.addInput(input)
            self.camera = device

            let output = AVCaptureMetadataOutput()
            guard self.session.canAddOutput(output) else {
                self.session.commitConfiguration()
                DispatchQueue.main.async { self.reportUnavailable() }
                return
            }
            self.session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: self.sessionQueue)
            if output.availableMetadataObjectTypes.contains(.qr) {
                output.metadataObjectTypes = [.qr]
            }
            self.session.commitConfiguration()
            self.configured = true
            DispatchQueue.main.async {
                self.updateOrientation()
                self.startIfNeeded()
            }
        }
    }

    private func startIfNeeded() {
        guard appeared, configured else { return }
        sessionQueue.async { [weak self] in
            guard let self, self.configured, !self.session.isRunning else { return }
            self.session.startRunning()
            DispatchQueue.main.async { self.updateOrientation() }
        }
    }

    private func updateOrientation() {
        guard let camera, let preview = (view as? CameraPreviewView)?.videoLayer else { return }
        let coordinator = AVCaptureDevice.RotationCoordinator(device: camera, previewLayer: preview)
        let angle = coordinator.videoRotationAngleForHorizonLevelPreview
        guard let connection = preview.connection, connection.isVideoRotationAngleSupported(angle) else { return }
        if connection.videoRotationAngle != angle {
            connection.videoRotationAngle = angle
        }
    }

    private func reportUnavailable() {
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.isBeingDismissed else { return }
            self.onUnavailable?()
        }
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard !didEmit,
              let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              object.type == .qr,
              let value = object.stringValue,
              !value.isEmpty
        else { return }
        didEmit = true
        session.stopRunning()
        DispatchQueue.main.async { [weak self] in
            self?.onCode?(value)
        }
    }
}

private final class CameraPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var videoLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }
}
