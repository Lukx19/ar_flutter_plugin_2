import XCTest
@testable import ar_flutter_plugin_2

/// Durable store names, hashes and records against the Android
/// `DurableSessionStoreV2` encodings. The goldens come from an independent
/// Python transcription of the Kotlin code (Java `DataOutputStream`,
/// `writeUTF`, UTF-8 `toByteArray`) for the fixture attempt of
/// `CaptureFixture.request()`; there is no JVM on the build host.
final class DurableStoreEncodingTests: XCTestCase {
  let request = CaptureFixture.request()

  func testReservationBoundsEqualAndroid() {
    XCTAssertEqual(request.accepted.reservation.physicalStoreBytes, 34_881_536)
    XCTAssertEqual(request.accepted.reservation.rollbackBytes, 33_816_576)
  }

  func testAcceptedTemplateAndRequestHashesEqualAndroid() {
    XCTAssertEqual(DurableStoreEncoding.acceptedHash(request.accepted), "ea05f93ec0597022acbec613b42645c71b298d597ab39a9164e788758dc663f5")
    XCTAssertEqual(DurableStoreEncoding.templateHash(request.template), "924d9c0ec6f17090fe4caa6e145b5239a66b27e673a821d14ecc67f88d894244")
    XCTAssertEqual(DurableStoreEncoding.requestHash(request), "94d7a8f5ea697d82006e746af24caa9975dce07986d75a4dff552a8f2a525309")
    let digest = DurableStoreEncoding.sha256(CaptureFixture.jpeg)
    var committed = request
    committed.components = [DurableComponentDescriptor(kind: .jpeg, byteLength: Int64(CaptureFixture.jpeg.count), sha256: digest,
                                                       durableObjectId: DurableStoreEncoding.hex(digest))]
    committed.exposureTimestampNanoseconds = 1_000_000
    XCTAssertEqual(DurableStoreEncoding.requestHash(committed), "80df787568709cd10266c528fadd569048cbf0d7e4b477e7695e304a61917dbf")
  }

  func testNamesEqualAndroid() {
    XCTAssertEqual(DurableStoreEncoding.safeName("session-ios"), "bfe0190c7d522f78adc769817d0ca814cf7fd6d83d24abf297bcad17cfe10ac6")
    XCTAssertEqual(DurableStoreEncoding.safeName("commit-1"), "69fdd6276a37f5e42af7f0405a21703b605543d088242983ac425fa848b38906")
    XCTAssertEqual(DurableStoreEncoding.safeName(request.accepted.identity.attemptId), "e94ebffc90c4bfd8a755f41c8d748d3d834378fc0e076eee9df77e188070bd73")
    XCTAssertEqual(DurableStoreEncoding.reservationOwner(request.accepted.identity),
                   "capture:bfe0190c7d522f78adc769817d0ca814cf7fd6d83d24abf297bcad17cfe10ac6:69fdd6276a37f5e42af7f0405a21703b605543d088242983ac425fa848b38906")
  }

  func testExactIdentityIsJavaWriteUtfAndRoundTrips() {
    let encoded = DurableStoreEncoding.exactIdentityBase64(request.accepted.identity)
    XCTAssertEqual(encoded, "ACQ2ZjFjMmQzZS0wMDAwLTQwMDAtODAwMC0wMDAwMDAwMDAwMDEACGNvbW1pdC0xAAAAAAAAAAEAC3Nlc3Npb24taW9zAAAAAAAAAAIAB2dyb3VwLWEAAAAAAAAAAwAOYXItc2Vzc2lvbi1pb3MABDQyNDIAAAAAAAAAAwAYc2Vzc2lvbi1pb3M6Z3JvdXAtYTo0MjQyAAAAAAAAAAEAAAAAAAAAAQ==")
    XCTAssertEqual(DurableStoreEncoding.exactIdentity(base64: encoded), request.accepted.identity)
    var unusual = request.accepted.identity
    unusual.attemptId = "a\u{0}é😀"
    XCTAssertEqual(DurableStoreEncoding.exactIdentity(base64: DurableStoreEncoding.exactIdentityBase64(unusual)), unusual)
  }

  func testRootRecordAndReceiptHashEqualAndroid() {
    let digest = DurableStoreEncoding.sha256(CaptureFixture.jpeg)
    let root = DurableCaptureStore.rootRecord(
      requestHash: "80df787568709cd10266c528fadd569048cbf0d7e4b477e7695e304a61917dbf", revision: 1,
      components: [DurableComponentDescriptor(kind: .jpeg, byteLength: Int64(CaptureFixture.jpeg.count), sha256: digest, durableObjectId: "")],
      prior: nil, priorPrevious: nil)
    let rootHash = DurableStoreEncoding.hex(DurableStoreEncoding.sha256(root))
    XCTAssertEqual(rootHash, "780001b3ed620e8e96135827fe6961238b262b52ebac89108d43f2dc0036af53")
    XCTAssertEqual(DurableStoreEncoding.receiptHash(requestHash: "80df787568709cd10266c528fadd569048cbf0d7e4b477e7695e304a61917dbf", rootHash: rootHash),
                   "fc0c509d85b124bd7772e94c0bf6f4fe1badf03801ef967f9306a5c027767b3f")
    let parsed = CaptureRootRecords.parseRoot(expectedHash: rootHash, bytes: root)
    XCTAssertEqual(parsed?.revision, 1)
    XCTAssertEqual(parsed?.components, [CaptureRootComponent(kind: "JPEG", length: Int64(CaptureFixture.jpeg.count), hash: DurableStoreEncoding.hex(digest))])
    XCTAssertNil(CaptureRootRecords.parseRoot(expectedHash: String(repeating: "0", count: 64), bytes: root), "content hash is verified")
  }

  func testPropertiesAreSortedUnescapedAndReadLikeJava() {
    let bytes = DurableStoreEncoding.propertiesBytes(["b": "2", "a": "x=y:z", "acceptedIdentity": "AB+/=="])
    XCTAssertEqual(String(decoding: bytes, as: UTF8.self), "a=x=y:z\nacceptedIdentity=AB+/==\nb=2\n")
    XCTAssertEqual(DurableStoreEncoding.parseProperties(bytes), ["b": "2", "a": "x=y:z", "acceptedIdentity": "AB+/=="])
    let java = "# comment\n! other\n  key1 = value one\nkey2:v2\nkey3 v3\nmulti=a\\\n    b\nescaped=\\u0041\\tz\n"
    XCTAssertEqual(DurableStoreEncoding.parseProperties(Array(java.utf8)),
                   ["key1": "value one", "key2": "v2", "key3": "v3", "multi": "ab", "escaped": "A\tz"])
  }

  func testDoublesUseJavaToString() {
    let cases: [(Double, String)] = [
      (1, "1.0"), (0, "0.0"), (-0.5, "-0.5"), (0.1, "0.1"), (0.001, "0.001"), (0.0001, "1.0E-4"),
      (123_456.789, "123456.789"), (9_999_999, "9999999.0"), (10_000_000, "1.0E7"), (12_345_678.9, "1.23456789E7"),
      (-2.5e-7, "-2.5E-7"), (1.0 / 3.0, "0.3333333333333333"),
    ]
    for (value, expected) in cases { XCTAssertEqual(DurableStoreEncoding.javaDouble(value), expected, "\(value)") }
  }
}
