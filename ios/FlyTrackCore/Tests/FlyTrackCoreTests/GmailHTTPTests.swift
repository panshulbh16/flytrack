import Foundation
import XCTest
@testable import FlyTrackCore

final class GmailHTTPTests: XCTestCase {
    private func reason(_ reason: String) -> Data {
        Data(#"{"error":{"code":403,"message":"x","errors":[{"domain":"global","reason":"\#(reason)"}]}}"#.utf8)
    }

    func testSuccessIsNoFailure() {
        XCTAssertNil(gmailFailure(status: 200, body: Data()))
        XCTAssertNil(gmailFailure(status: 204, body: Data()))
    }

    func testUnauthorizedAsksForAFreshToken() {
        XCTAssertEqual(gmailFailure(status: 401, body: Data()), .unauthorized)
    }

    func testRateLimitsAndServerErrorsStopTheScan() {
        XCTAssertEqual(gmailFailure(status: 429, body: Data()), .rateLimited)
        XCTAssertEqual(gmailFailure(status: 500, body: Data()), .rateLimited)
        XCTAssertEqual(gmailFailure(status: 503, body: Data()), .rateLimited)
        for name in ["rateLimitExceeded", "userRateLimitExceeded", "dailyLimitExceeded"] {
            XCTAssertEqual(gmailFailure(status: 403, body: reason(name)), .rateLimited, name)
        }
    }

    func testMissingScopeOrForbiddenLosesAccess() {
        XCTAssertEqual(gmailFailure(status: 403, body: reason("insufficientPermissions")), .accessLost)
        XCTAssertEqual(gmailFailure(status: 403, body: reason("forbidden")), .accessLost)
    }

    func testAnythingElseIsOther() {
        XCTAssertEqual(gmailFailure(status: 400, body: Data()), .other)
        XCTAssertEqual(gmailFailure(status: 404, body: Data()), .other)
        XCTAssertEqual(gmailFailure(status: 0, body: Data()), .other)
        XCTAssertEqual(gmailFailure(status: 403, body: Data("not json".utf8)), .other)
        XCTAssertEqual(gmailFailure(status: 403, body: reason("accessNotConfigured")), .other)
    }

    /// GoogleSignIn keeps one user: after GoogleLinker links another Google account, that is the user restored.
    func testAnotherGoogleAccountNeverGetsAGmailToken() {
        XCTAssertEqual(GmailAccount.silentResult(.granted("t"), restoredEmail: "Me@Gmail.com", storedEmail: "me@gmail.com"), .granted("t"))
        XCTAssertEqual(GmailAccount.silentResult(.granted("t"), restoredEmail: "work@company.com", storedEmail: "me@gmail.com"), .needsUser)
        XCTAssertEqual(GmailAccount.silentResult(.granted("t"), restoredEmail: nil, storedEmail: "me@gmail.com"), .needsUser)
        XCTAssertEqual(GmailAccount.silentResult(.granted("t"), restoredEmail: "me@gmail.com", storedEmail: nil), .needsUser)
        XCTAssertEqual(GmailAccount.silentResult(.offline, restoredEmail: nil, storedEmail: "me@gmail.com"), .offline)
        XCTAssertEqual(GmailAccount.silentResult(.needsUser, restoredEmail: nil, storedEmail: "me@gmail.com"), .needsUser)
    }
}
