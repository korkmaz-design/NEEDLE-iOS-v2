import SwiftUI
import MusicKit

struct SearchView: View {
    @ObservedObject var music: MusicController
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                HStack {
                    TextField("Şarkı veya sanatçı ara", text: $query).submitLabel(.search)
                        .onSubmit { Task { await music.search(query) } }
                    Button("Ara") { Task { await music.search(query) } }.tint(.red)
                }
                ForEach(music.results, id: \.id) { song in
                    Button {
                        Task { await music.play(song); dismiss() }
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(song.title).foregroundStyle(.primary)
                            Text(song.artistName).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
            }.navigationTitle("Apple Music")
                .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Bitti") { dismiss() } } }
        }.task { await music.requestAccess() }
    }
}
