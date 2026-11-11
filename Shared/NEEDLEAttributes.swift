import ActivityKit

struct NEEDLEAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var lyricLine: String
        var trackTitle: String
        var artist: String
        var isPlaying: Bool
    }

    var sessionName: String
}
