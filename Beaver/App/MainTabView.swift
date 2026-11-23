import SwiftUI

struct MainTabView: View {
    var body: some View {
        TabView {
            WeekView()
                .tabItem { Label("Week", systemImage: "calendar") }
            RosterView()
                .tabItem { Label("Roster", systemImage: "person.2") }
            MoneyView()
                .tabItem { Label("Money", systemImage: "dollarsign.circle") }
            PlanView()
                .tabItem { Label("Plan", systemImage: "flag") }
        }
    }
}
