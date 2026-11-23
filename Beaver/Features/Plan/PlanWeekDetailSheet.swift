import SwiftUI
import BeaverCore

/// One forecast week: its pay events, bills and totals.
struct PlanWeekDetailSheet: View {
    let detail: PlanWeekDetail
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if detail.isDanger {
                    Section {
                        Label("Spending is more than income this week.", systemImage: "exclamationmark.triangle.fill")
                            .foregroundStyle(Palette.negative)
                    }
                }
                Section("Pay") {
                    if detail.pays.isEmpty { Text("No payday this week").foregroundStyle(.secondary) }
                    ForEach(detail.pays) { pay in
                        VStack(alignment: .leading, spacing: 2) {
                            HStack {
                                Text(pay.sourceName)
                                Spacer()
                                Text(pay.isActual ? (pay.actualText ?? "") : pay.expectedText).monospacedDigit()
                            }
                            HStack {
                                Text("\(pay.paydayText) · \(pay.isActual ? "actual" : "est.")")
                                if let diff = pay.differenceText {
                                    Spacer()
                                    Text("\(diff) vs \(pay.expectedText)").foregroundStyle(pay.differenceTone.color)
                                }
                            }
                            .font(.caption).foregroundStyle(.secondary).monospacedDigit()
                        }
                        .accessibilityElement(children: .combine)
                    }
                    if let extra = detail.extraIncomeText {
                        LabeledContent("What-if extra income") { Text(extra).monospacedDigit() }
                    }
                }
                Section("Bills") {
                    if detail.bills.isEmpty { Text("No bills due").foregroundStyle(.secondary) }
                    ForEach(detail.bills) { bill in
                        HStack {
                            Label(bill.name, systemImage: bill.isPaid ? "checkmark.circle.fill" : bill.symbol)
                            Spacer()
                            VStack(alignment: .trailing) {
                                Text(bill.amountText).monospacedDigit()
                                Text(bill.isPaid ? "Paid · \(bill.dateText)" : bill.dateText)
                                    .font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .accessibilityElement(children: .combine)
                    }
                }
                Section("Totals") {
                    LabeledContent("Income") { Text(detail.incomeText).monospacedDigit() }
                    LabeledContent("Bills") { Text(detail.billsText).monospacedDigit() }
                    LabeledContent("Living") { Text(detail.livingText).monospacedDigit() }
                    LabeledContent("Surplus") {
                        Text(detail.surplusText).bold().monospacedDigit().foregroundStyle(detail.surplusTone.color)
                    }
                    if detail.isDeadlineWeek {
                        Text("Deadline week: only pay and bills up to the deadline count.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(detail.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }
}
