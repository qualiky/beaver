import SwiftUI

/// Search and ordering for the time zone list. Pure, so it is unit-tested.
enum TimeZoneChoices {
    /// "Australia/Sydney" shown as "Australia/Sydney" with spaces for underscores.
    static func display(_ id: String) -> String { id.replacingOccurrences(of: "_", with: " ") }

    /// All known identifiers with `current` first (added if unknown), filtered by `query` over the identifier and its
    /// spaced display form. The current zone stays at the top while it matches.
    static func filtered(query: String, current: String,
                         all: [String] = TimeZone.knownTimeZoneIdentifiers) -> [String] {
        let sorted = all.sorted()
        let ordered = [current] + sorted.filter { $0 != current }
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return ordered }
        return ordered.filter { id in
            id.lowercased().contains(needle) || display(id).lowercased().contains(needle)
        }
    }
}

/// A row that pushes a searchable list of time zones. Needs a parent `NavigationStack`.
struct TimeZonePicker: View {
    let title: LocalizedStringKey
    @Binding var selection: String

    init(_ title: LocalizedStringKey = "Time zone", selection: Binding<String>) {
        self.title = title
        _selection = selection
    }

    var body: some View {
        NavigationLink {
            TimeZoneListView(selection: $selection)
        } label: {
            LabeledContent(title, value: TimeZoneChoices.display(selection))
        }
    }
}

private struct TimeZoneListView: View {
    @Binding var selection: String
    @State private var query = ""
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let zones = TimeZoneChoices.filtered(query: query, current: selection)
        List {
            ForEach(zones, id: \.self) { id in
                Button {
                    selection = id
                    dismiss()
                } label: {
                    HStack {
                        Text(TimeZoneChoices.display(id))
                        Spacer()
                        if id == selection {
                            Image(systemName: "checkmark").foregroundStyle(.tint).accessibilityHidden(true)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(id == selection ? .isSelected : [])
            }
            if zones.isEmpty {
                ContentUnavailableView.search(text: query)
            }
        }
        .navigationTitle("Time zone")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.visible, for: .navigationBar)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search time zones")
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
    }
}
