import Foundation
import Combine
import MusicKit
import ActivityKit

@MainActor
final class MusicController: ObservableObject {
    @Published var currentTitle = ""
    @Published var currentArtist = ""
    @Published var isPlaying = false
    @Published var statusMessage = ""
    @Published var results: [Song] = []

    private let player = ApplicationMusicPlayer.shared
    private var liveActivity: Activity<NEEDLEAttributes>?

    func requestAccess() async {
        let status = await MusicAuthorization.request()
        switch status {
        case .authorized: statusMessage = "Apple Music erişimi verildi."
        case .denied: statusMessage = "Apple Music erişimini Ayarlar'dan açmalısın."
        case .restricted: statusMessage = "Apple Music erişimi kısıtlanmış."
        case .notDetermined: statusMessage = "Erişim henüz belirlenmedi."
        @unknown default: statusMessage = "Bilinmeyen izin durumu."
        }
    }

    func search(_ term: String) async {
        guard !term.trimmingCharacters(in: .whitespaces).isEmpty else { return }
        do {
            var request = MusicCatalogSearchRequest(term: term, types: [Song.self])
            request.limit = 20
            let response = try await request.response()
            results = Array(response.songs)
            statusMessage = results.isEmpty ? "Şarkı bulunamadı." : "\(results.count) sonuç bulundu."
        } catch { statusMessage = "Arama hatası: \(error.localizedDescription)" }
    }

    func play(_ song: Song) async {
        do {
            player.queue = [song]
            try await player.play()
            currentTitle = song.title
            currentArtist = song.artistName
            isPlaying = true
            await startOrUpdateLiveActivity()
            statusMessage = song.hasLyrics
                ? "Apple Music söz bulunduğunu bildiriyor. Senkronize söz metni için ayrıca lisanslı söz kaynağı gerekir."
                : "Bu şarkı için Apple Music katalogunda söz bilgisi yok."
        } catch { statusMessage = "Çalma hatası: \(error.localizedDescription)" }
    }

    func togglePlayback() async {
        do {
            if isPlaying {
                player.pause()
                isPlaying = false
            } else {
                try await player.play()
                isPlaying = true
            }
            await startOrUpdateLiveActivity()
        } catch { statusMessage = "Oynatma hatası: \(error.localizedDescription)" }
    }

    private func startOrUpdateLiveActivity() async {
        guard ActivityAuthorizationInfo().areActivitiesEnabled else {
            statusMessage = "Live Activities iPhone Ayarları'ndan etkinleştirilmeli."
            return
        }
        let state = NEEDLEAttributes.ContentState(
            lyricLine: "NEEDLE • NOW PLAYING",
            trackTitle: currentTitle,
            artist: currentArtist,
            isPlaying: isPlaying
        )
        let content = ActivityContent(state: state, staleDate: Date().addingTimeInterval(60 * 60))
        do {
            if let activity = liveActivity {
                await activity.update(content)
            } else {
                liveActivity = try Activity.request(
                    attributes: NEEDLEAttributes(sessionName: "NEEDLE SESSION"),
                    content: content,
                    pushType: nil
                )
            }
        } catch {
            statusMessage = "Kilit ekranı etkinliği başlatılamadı: \(error.localizedDescription)"
        }
    }
}
