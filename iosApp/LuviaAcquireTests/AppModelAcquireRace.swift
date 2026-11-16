import Foundation
import LuviaShared
@testable import Luvia

/// Production-caller bodies for the acquire race.
/// `LuviaAcquireTests` invokes each one. This file is not in the shipping app target.
/// Linux has no `xcodebuild`, so this file is uncompiled and unexecuted here.
/// ChatView draft retention and the MF6 handoff caller are not these cases.

final class RecordingTerminalControl: TerminalControlHandle, @unchecked Sendable {
    private(set) var closed = false
    private(set) var waitingForSubmit = false
    private var submitWait: CheckedContinuation<TerminalWrite, Error>?

    func close() {
        closed = true
    }

    func submitText(text: String) async throws -> TerminalWrite {
        try await withCheckedThrowingContinuation { continuation in
            waitingForSubmit = true
            submitWait = continuation
        }
    }

    func sendKey(key: TerminalKey) async throws -> TerminalWrite {
        .accepted
    }

    func finishSubmit(_ result: TerminalWrite) {
        waitingForSubmit = false
        submitWait?.resume(returning: result)
        submitWait = nil
    }
}

final class DeferredTerminalOpens: @unchecked Sendable {
    private var waits: [CheckedContinuation<TerminalAcquireOpen, Error>] = []

    var pending: Int { waits.count }

    func open(_ hostId: String, _ identity: TerminalIdentity) async throws -> TerminalAcquireOpen {
        try await withCheckedThrowingContinuation { continuation in
            waits.append(continuation)
        }
    }

    func finish(at index: Int, _ opened: TerminalAcquireOpen) {
        guard waits.indices.contains(index) else { return }
        waits.remove(at: index).resume(returning: opened)
    }
}

enum AppModelAcquireRace {
    @MainActor
    static func deferredConflictLeavesTheNewerControl() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        model.requestTerminalControl()
        try await until { opens.pending >= 2 }
        let newer = RecordingTerminalControl()
        opens.finish(at: 1, .installed(newer))
        try await until { holds(model, newer) }
        opens.finish(at: 0, .rejected(Failure.ControlConflict(message: "held")))
        await _Concurrency.Task.yield()
        try check(holds(model, newer), "newer control remains after the older conflict")
        try check(newer.closed == false, "older conflict does not close the newer control")
        try check(model.holdsTerminalControl, "newer control stays held")
    }

    @MainActor
    static func roleLossDoesNotInstall() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        var host = controllerHost()
        host.isController = false
        model.stageSelectedHost(host)
        let handle = RecordingTerminalControl()
        opens.finish(at: 0, .installed(handle))
        await _Concurrency.Task.yield()
        try check(handle.closed, "role loss closes the handle from the open")
        try check(model.terminalControl == nil, "role loss does not install")
        try check(model.holdsTerminalControl == false, "role loss does not hold control")
    }

    @MainActor
    static func locatorChangeClosesTheLateHandle() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        var host = controllerHost()
        host.terminalLocator = TerminalLocator(serverGeneration: "gen", terminalId: "term-b", paneId: "8")
        host.paneTerminalIds = ["8": "term-b"]
        model.uhp.selectedAgentID = nil
        model.stageSelectedHost(host)
        let handle = RecordingTerminalControl()
        opens.finish(at: 0, .installed(handle))
        await _Concurrency.Task.yield()
        try check(handle.closed, "locator change closes the late handle")
        try check(model.terminalControl == nil, "locator change does not install")
    }

    @MainActor
    static func rebindInvalidatesTheInflightAcquire() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        model.rebindTerminalIfPaneChanged(from: "7", to: "8")
        let handle = RecordingTerminalControl()
        opens.finish(at: 0, .installed(handle))
        await _Concurrency.Task.yield()
        try check(handle.closed, "rebind closes the late handle")
        try check(model.terminalControl == nil, "rebind does not keep the late handle")
    }

    @MainActor
    static func sameIdentityRefreshKeepsTheInstalledControl() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.openTerminal(paneId: "7")
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        let held = RecordingTerminalControl()
        opens.finish(at: 0, .installed(held))
        try await until { holds(model, held) }
        model.openTerminal(paneId: "7")
        try check(holds(model, held), "same identity refresh keeps the installed control")
        try check(held.closed == false, "same identity refresh does not close it")
    }

    @MainActor
    static func staleSubmitDoesNotCloseTheNewerControl() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        let older = RecordingTerminalControl()
        opens.finish(at: 0, .installed(older))
        try await until { holds(model, older) }
        let send = _Concurrency.Task { await model.sendTerminal("ls") }
        try await until { older.waitingForSubmit }
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        let newer = RecordingTerminalControl()
        opens.finish(at: 0, .installed(newer))
        try await until { holds(model, newer) }
        try check(older.closed, "the newer acquire closes the replaced handle")
        older.finishSubmit(.rejected(Failure.ControlConflict(message: "late")))
        await send.value
        try check(holds(model, newer), "stale submit leaves the newer control")
        try check(newer.closed == false, "stale submit does not close the newer control")
    }

    @MainActor
    static func currentConflictDropsOnlyTheMatchingControl() async throws {
        let opens = DeferredTerminalOpens()
        let model = caller(opens)
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        let held = RecordingTerminalControl()
        opens.finish(at: 0, .installed(held))
        try await until { holds(model, held) }
        model.requestTerminalControl()
        try await until { opens.pending >= 1 }
        opens.finish(at: 0, .rejected(Failure.ControlConflict(message: "held")))
        await _Concurrency.Task.yield()
        try check(held.closed, "the current conflict still drops that held control")
        try check(model.terminalControl == nil, "the current conflict leaves no handle")
    }

    @MainActor
    private static func caller(_ opens: DeferredTerminalOpens) -> AppModel {
        let open: (String, TerminalIdentity) async throws -> TerminalAcquireOpen = { host, identity in
            try await opens.open(host, identity)
        }
        let model = AppModel(terminalOpener: open, startCatalog: false)
        model.stageTerminalCaller(host: controllerHost(), paneId: "7", opener: open)
        return model
    }

    @MainActor
    private static func holds(_ model: AppModel, _ handle: TerminalControlHandle) -> Bool {
        guard let held = model.terminalControl else { return false }
        return ObjectIdentifier(held as AnyObject) == ObjectIdentifier(handle as AnyObject)
    }

    private static func controllerHost() -> HostViewState {
        HostViewState(
            id: "host-1",
            name: "host",
            address: "10.0.0.8",
            connection: .live,
            isController: true,
            terminalLocator: TerminalLocator(serverGeneration: "gen", terminalId: "term-a", paneId: "7"),
            paneTerminalIds: ["7": "term-a"]
        )
    }

    private static func until(_ ready: () -> Bool) async throws {
        for _ in 0..<40 {
            if ready() { return }
            await _Concurrency.Task.yield()
        }
        try check(false, "production caller did not reach the deferred open")
    }

    private static func check(_ condition: Bool, _ message: String) throws {
        if !condition {
            throw AcquireRaceError(message)
        }
    }
}

struct AcquireRaceError: Error, CustomStringConvertible {
    let description: String

    init(_ description: String) {
        self.description = description
    }
}
