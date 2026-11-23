import SwiftUI

struct MainTabView: View {
    @State private var exportBackup = false

    var body: some View {
        TabView {
            WeekView(onBackUp: { exportBackup = true })
                .tabItem { Label("Week", systemImage: "calendar") }
            RosterView()
                .tabItem { Label("Roster", systemImage: "person.2") }
            MoneyView()
                .tabItem { Label("Money", systemImage: "dollarsign.circle") }
            PlanView()
                .tabItem { Label("Plan", systemImage: "flag") }
        }
        .backupExportFlow(isPresented: $exportBackup)
        .payReminderSync()
    }
}
