import ActivityKit
import WidgetKit
import SwiftUI

struct NEEDLEActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: NEEDLEAttributes.self) { context in
            HStack(spacing: 12) {
                RoundedRectangle(cornerRadius: 3)
                    .fill(Color.red)
                    .frame(width: 3, height: 44)

                VStack(alignment: .leading, spacing: 5) {
                    Text(context.state.lyricLine)
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(.white)
                        .lineLimit(1)

                    Text("\(context.state.trackTitle) — \(context.state.artist)")
                        .font(.system(size: 10))
                        .foregroundStyle(.white.opacity(0.55))
                        .lineLimit(1)
                }

                Spacer()

                Image(systemName: context.state.isPlaying ? "waveform" : "pause.fill")
                    .foregroundStyle(.red)
            }
            .padding(14)
            .activityBackgroundTint(Color.black)

        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Image(systemName: "waveform").foregroundStyle(.red)
                }

                DynamicIslandExpandedRegion(.center) {
                    Text(context.state.trackTitle)
                        .font(.caption.bold())
                        .lineLimit(1)
                }

                DynamicIslandExpandedRegion(.bottom) {
                    Text(context.state.lyricLine)
                        .font(.caption)
                        .lineLimit(1)
                }

            } compactLeading: {
                Image(systemName: "waveform").foregroundStyle(.red)

            } compactTrailing: {
                Text("N").font(.caption.bold()).foregroundStyle(.red)

            } minimal: {
                Image(systemName: "waveform").foregroundStyle(.red)
            }
        }
    }
}
