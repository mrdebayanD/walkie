import SwiftUI
import UIKit

enum Theme {
    static let green = Color(red: 48 / 255, green: 209 / 255, blue: 88 / 255)      // systemGreen (dark)
    static let onGreen = Color(red: 0, green: 33 / 255, blue: 11 / 255)
    static let red = Color(red: 255 / 255, green: 69 / 255, blue: 58 / 255)         // systemRed (dark)
    static let redText = Color(red: 255 / 255, green: 105 / 255, blue: 97 / 255)
    static let orange = Color(red: 255 / 255, green: 159 / 255, blue: 10 / 255)
    static let secondary = Color(white: 0.92).opacity(0.62)
    static let card = Color(red: 28 / 255, green: 28 / 255, blue: 30 / 255)        // secondarySystemBackground
    static let card2 = Color(red: 44 / 255, green: 44 / 255, blue: 46 / 255)
    static let glassFill = Color(red: 118 / 255, green: 118 / 255, blue: 128 / 255).opacity(0.24)

    static func channelColor(_ name: String) -> Color {
        switch name {
        case "green": return green
        case "orange": return orange
        case "pink": return Color(red: 1, green: 55 / 255, blue: 95 / 255)
        case "purple": return Color(red: 191 / 255, green: 90 / 255, blue: 242 / 255)
        case "red": return red
        case "teal": return Color(red: 100 / 255, green: 210 / 255, blue: 1)
        case "yellow": return Color(red: 1, green: 214 / 255, blue: 10 / 255)
        default: return Color(red: 10 / 255, green: 132 / 255, blue: 1)
        }
    }

    /// A stable colour per person, so avatars look the same on every phone.
    static func personColor(_ id: String) -> Color {
        let palette: [Color] = [orange, Color(red: 165 / 255, green: 164 / 255, blue: 1), Color(red: 1, green: 100 / 255, blue: 130 / 255),
                                Color(red: 218 / 255, green: 143 / 255, blue: 1), Color(red: 100 / 255, green: 210 / 255, blue: 1), Color(red: 1, green: 214 / 255, blue: 10 / 255)]
        let h = id.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0x7fffffff }
        return palette[h % palette.count]
    }
}

enum Haptics {
    static func start() { UIImpactFeedbackGenerator(style: .medium).impactOccurred() }
    static func stop() { UIImpactFeedbackGenerator(style: .light).impactOccurred() }
    static func incoming() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    static func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
    static func tick() { UISelectionFeedbackGenerator().selectionChanged() }
}

/// Frosted "glass" background: the real Liquid Glass on iOS 26, a material blur before that.
struct GlassBackground: ViewModifier {
    var cornerRadius: CGFloat
    func body(content: Content) -> some View {
        #if compiler(>=6.2) // Xcode 26 SDK
        if #available(iOS 26.0, *) {
            content.glassEffect(.regular, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
        } else {
            fallback(content)
        }
        #else
        fallback(content)
        #endif
    }

    private func fallback(_ content: Content) -> some View {
        content
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: cornerRadius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(.white.opacity(0.14), lineWidth: 0.5))
    }
}

extension View {
    func glass(_ radius: CGFloat = 22) -> some View { modifier(GlassBackground(cornerRadius: radius)) }
}

struct Avatar: View {
    let member: Member?
    var name: String = ""
    var size: CGFloat = 44
    var showStatus = false

    var body: some View {
        let id = member?.id ?? name
        let color = Theme.personColor(id)
        let initials = member?.initials ?? Initials.of(name)
        ZStack(alignment: .bottomTrailing) {
            Circle().fill(color.opacity(0.24))
                .overlay(Text(initials).font(.system(size: size * 0.36, weight: .semibold)).foregroundStyle(color))
                .frame(width: size, height: size)
                .opacity(showStatus && member?.duty == false ? 0.5 : 1)
            if showStatus, let m = member {
                Circle().fill(m.duty ? Theme.green : Color(white: 0.39))
                    .frame(width: size * 0.3, height: size * 0.3)
                    .overlay(Circle().strokeBorder(.black, lineWidth: 2.5))
                    .accessibilityHidden(true)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(member.map { "\($0.name), \($0.duty ? "on duty" : "off duty")" } ?? name)
    }
}

struct SectionHeader: View {
    let text: String
    var body: some View {
        Text(text.uppercased())
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Theme.secondary)
            .padding(.leading, 16)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct Waveform: View {
    let level: Float
    let color: Color
    var body: some View {
        TimelineView(.animation(minimumInterval: 0.08)) { ctx in
            let t = ctx.date.timeIntervalSinceReferenceDate
            HStack(spacing: 4) {
                ForEach(0..<24, id: \.self) { i in
                    let wobble = (sin(t * 9 + Double(i) * 0.7) + 1) / 2
                    let h = 6 + CGFloat(Double(level) * 30 * (0.4 + 0.6 * wobble))
                    Capsule().fill(color).frame(width: 4, height: h)
                }
            }
            .frame(height: 36)
        }
        .accessibilityHidden(true)
    }
}
