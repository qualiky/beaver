import Testing
@testable import Beaver

struct SmokeTests {
    @MainActor
    @Test func moneyTextFormatsCents() {
        let text = MoneyText.format(cents: 123_456, currencyCode: "USD")
        #expect(text.contains("1,234.56"))
    }

    @MainActor
    @Test func onboardingStartsIncomplete() {
        #expect(AppModel().onboardingDone == false)
    }
}
