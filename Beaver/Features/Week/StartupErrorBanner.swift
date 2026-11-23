import SwiftUI

/// Shown above the whole app when the database could not be opened and Beaver fell back to memory.
/// It cannot be dismissed: nothing entered in this state survives closing the app.
struct StartupErrorBanner: View {
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Label("Your changes won't be saved", systemImage: "exclamationmark.octagon.fill")
                .font(.subheadline.weight(.bold))
            Text("Beaver couldn't use its database, so it is running in temporary memory. Anything you enter will be lost when you close the app. Restart the app; if this keeps happening, free up storage on your phone.")
                .font(.footnote)
            Text(detail).font(.caption2).opacity(0.85)
        }
        .foregroundStyle(.white)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(red: 0.62, green: 0.07, blue: 0.09))
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isStaticText)
    }
}

#Preview { StartupErrorBanner(detail: "unable to open database file") }
