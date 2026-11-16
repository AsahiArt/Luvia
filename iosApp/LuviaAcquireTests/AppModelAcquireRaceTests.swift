import XCTest
@testable import Luvia

/// Invokes the seven production `AppModel` acquire and send cases.
/// Not part of app startup. Uncompiled and unexecuted on Linux.
final class AppModelAcquireRaceTests: XCTestCase {
    func testDeferredConflictLeavesTheNewerControl() async throws {
        try await AppModelAcquireRace.deferredConflictLeavesTheNewerControl()
    }

    func testRoleLossDoesNotInstall() async throws {
        try await AppModelAcquireRace.roleLossDoesNotInstall()
    }

    func testLocatorChangeClosesTheLateHandle() async throws {
        try await AppModelAcquireRace.locatorChangeClosesTheLateHandle()
    }

    func testRebindInvalidatesTheInflightAcquire() async throws {
        try await AppModelAcquireRace.rebindInvalidatesTheInflightAcquire()
    }

    func testSameIdentityRefreshKeepsTheInstalledControl() async throws {
        try await AppModelAcquireRace.sameIdentityRefreshKeepsTheInstalledControl()
    }

    func testStaleSubmitDoesNotCloseTheNewerControl() async throws {
        try await AppModelAcquireRace.staleSubmitDoesNotCloseTheNewerControl()
    }

    func testCurrentConflictDropsOnlyTheMatchingControl() async throws {
        try await AppModelAcquireRace.currentConflictDropsOnlyTheMatchingControl()
    }
}
