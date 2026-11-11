import SwiftUI
import MusicKit

struct ContentView: View {
    @StateObject private var music = MusicController()
    @State private var lyricPreview = "I FOLLOW THE LIGHTS TONIGHT"
    @State private var showSearch = false

    var body: some View {
        ZStack {
            Color(red: 0.025, green: 0.018, blue: 0.03).ignoresSafeArea()
            ConstellationBackdrop().ignoresSafeArea()
            VStack(spacing: 22) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("NEEDLE").font(.system(size: 13, weight: .black, design: .rounded))
                            .tracking(5).foregroundStyle(Color(red: 1, green: 0.22, blue: 0.27))
                        Text("MUSIC VISUALIZER").font(.system(size: 9, weight: .medium, design: .monospaced))
                            .tracking(2.5).foregroundStyle(.white.opacity(0.48))
                    }
                    Spacer()
                    Button { showSearch = true } label: {
                        Image(systemName: "magnifyingglass").font(.system(size: 17))
                            .foregroundStyle(.white).padding(12)
                            .background(.white.opacity(0.08), in: Circle())
                    }
                }
                Spacer(minLength: 12)
                ZStack {
                    Circle().stroke(.red.opacity(0.15), lineWidth: 1).frame(width: 270, height: 270)
                    Circle().stroke(.red.opacity(0.08), style: StrokeStyle(lineWidth: 1, dash: [3, 8]))
                        .frame(width: 220, height: 220)
                    ForEach(0..<12, id: \.self) { i in
                        Capsule().fill(Color.red.opacity(i % 3 == 0 ? 0.85 : 0.28))
                            .frame(width: 1, height: i % 3 == 0 ? 128 : 78)
                            .offset(y: -62).rotationEffect(.degrees(Double(i) * 30))
                    }
                    Image(systemName: "sparkle").font(.system(size: 42, weight: .ultraLight))
                        .foregroundStyle(.white.opacity(0.85)).shadow(color: .red, radius: 24)
                }.frame(maxWidth: .infinity).padding(.vertical, 10)
                Text(lyricPreview).font(.system(size: 22, weight: .black, design: .rounded))
                    .tracking(2.4).multilineTextAlignment(.center).foregroundStyle(.white)
                    .shadow(color: .red.opacity(0.8), radius: 18).minimumScaleFactor(0.65).lineLimit(2)
                VStack(spacing: 5) {
                    Text(music.currentTitle.isEmpty ? "SELECT A TRACK" : music.currentTitle.uppercased())
                        .font(.system(size: 12, weight: .bold)).tracking(2).foregroundStyle(.white).lineLimit(1)
                    Text(music.currentArtist.isEmpty ? "APPLE MUSIC • NEEDLE SESSION" : music.currentArtist)
                        .font(.system(size: 11, weight: .medium)).foregroundStyle(.white.opacity(0.48)).lineLimit(1)
                }
                HStack(spacing: 28) {
                    Button { Task { await music.togglePlayback() } } label: {
                        Image(systemName: music.isPlaying ? "pause.fill" : "play.fill")
                            .font(.system(size: 18, weight: .bold)).foregroundStyle(.black)
                            .frame(width: 58, height: 58)
                            .background(Color(red: 1, green: 0.22, blue: 0.27), in: Circle())
                    }
                    Button { Task { await music.requestAccess() } } label: {
                        Image(systemName: "apple.music").font(.system(size: 19)).foregroundStyle(.white)
                            .frame(width: 48, height: 48).background(.white.opacity(0.08), in: Circle())
                    }
                }
                if !music.statusMessage.isEmpty {
                    Text(music.statusMessage).font(.system(size: 10, weight: .medium))
                        .foregroundStyle(.white.opacity(0.55)).multilineTextAlignment(.center)
                }
                Spacer(minLength: 8)
            }.padding(.horizontal, 25).padding(.top, 18).padding(.bottom, 12)
        }
        .sheet(isPresented: $showSearch) { SearchView(music: music).presentationDetents([.medium, .large]) }
    }
}

struct ConstellationBackdrop: View {
    private let points: [CGPoint] = [
        .init(x: 0.10, y: 0.18), .init(x: 0.33, y: 0.12), .init(x: 0.57, y: 0.22),
        .init(x: 0.86, y: 0.14), .init(x: 0.18, y: 0.48), .init(x: 0.77, y: 0.53),
        .init(x: 0.29, y: 0.79), .init(x: 0.63, y: 0.84), .init(x: 0.92, y: 0.77)
    ]
    var body: some View {
        Canvas { context, size in
            let p = points.map { CGPoint(x: $0.x * size.width, y: $0.y * size.height) }
            let links = [(0,1),(1,2),(2,3),(0,4),(4,6),(2,5),(5,8),(6,7),(7,8),(1,4),(2,7)]
            for (a,b) in links {
                var path = Path(); path.move(to: p[a]); path.addLine(to: p[b])
                context.stroke(path, with: .color(.red.opacity(0.19)), lineWidth: 0.7)
            }
            for point in p {
                context.fill(Path(ellipseIn: CGRect(x: point.x - 1.5, y: point.y - 1.5, width: 3, height: 3)),
                             with: .color(.red.opacity(0.75)))
            }
        }
    }
}
