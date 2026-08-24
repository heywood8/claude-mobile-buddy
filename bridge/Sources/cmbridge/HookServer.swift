import Foundation
import NIOCore
import NIOPosix
import NIOHTTP1

/// Receives Claude Code hook events over loopback HTTP.
///
/// The `PermissionRequest` response is held open while the phone is asked, which is the whole
/// point: Claude Code waits on this request, so the terminal stays blocked until you tap or
/// the window closes.
final class HookServer {
    private let coordinator: Coordinator
    private let log: Logger
    private let group = MultiThreadedEventLoopGroup(numberOfThreads: 1)

    init(coordinator: Coordinator, log: Logger) {
        self.coordinator = coordinator
        self.log = log
    }

    func run(port: Int) throws {
        let bootstrap = ServerBootstrap(group: group)
            .serverChannelOption(ChannelOptions.backlog, value: 16)
            .serverChannelOption(ChannelOptions.socketOption(.so_reuseaddr), value: 1)
            .childChannelInitializer { [coordinator, log] channel in
                channel.pipeline.configureHTTPServerPipeline().flatMap {
                    channel.pipeline.addHandler(HookHandler(coordinator: coordinator, log: log))
                }
            }

        // Loopback only. This endpoint decides whether shell commands run.
        let channel = try bootstrap.bind(host: "127.0.0.1", port: port).wait()
        log.info("listening on http://127.0.0.1:\(port)")
        try channel.closeFuture.wait()
    }
}

private final class HookHandler: ChannelInboundHandler {
    typealias InboundIn = HTTPServerRequestPart
    typealias OutboundOut = HTTPServerResponsePart

    private let coordinator: Coordinator
    private let log: Logger
    private var head: HTTPRequestHead?
    private var body: ByteBuffer?

    init(coordinator: Coordinator, log: Logger) {
        self.coordinator = coordinator
        self.log = log
    }

    func channelRead(context: ChannelHandlerContext, data: NIOAny) {
        switch unwrapInboundIn(data) {
        case .head(let head):
            self.head = head
            body = nil
        case .body(var chunk):
            if body == nil { body = chunk } else { body!.writeBuffer(&chunk) }
        case .end:
            guard let head else { return }
            let path = String(head.uri.prefix(while: { $0 != "?" }))
            let payload = body.map { Data($0.readableBytesView) } ?? Data()
            // NIO channels are safe to write from any thread, so the reply can be produced
            // off the event loop once the phone has answered.
            let channel = context.channel
            let coordinator = self.coordinator
            let log = self.log
            Task {
                let response = await Self.route(path: path, payload: payload,
                                                coordinator: coordinator, log: log)
                Self.reply(on: channel, body: response)
            }
        }
    }

    private static func route(path: String, payload: Data,
                              coordinator: Coordinator, log: Logger) async -> Data {
        switch path {
        case "/permission-request":
            guard let request = HookRequest(body: payload) else {
                log.error("unparseable PermissionRequest payload")
                return HookResponse.noDecision.jsonData
            }
            return await coordinator.decide(request).jsonData

        case "/session-start":
            if let request = HookRequest(body: payload) {
                await coordinator.sessionStarted(request.sessionID)
            }
            return Data("{}".utf8)

        case "/session-end":
            if let request = HookRequest(body: payload) {
                await coordinator.sessionEnded(request.sessionID)
            }
            return Data("{}".utf8)

        case "/tool-use":
            if let request = HookRequest(body: payload) {
                await coordinator.recordToolUse(tool: request.toolName, hint: request.hint)
            }
            return Data("{}".utf8)

        case "/health":
            return Data("{\"ok\":true}".utf8)

        default:
            log.error("unknown hook path \(path)")
            return Data("{}".utf8)
        }
    }

    private static func reply(on channel: Channel, body: Data) {
        var buffer = channel.allocator.buffer(capacity: body.count)
        buffer.writeBytes(body)

        var headers = HTTPHeaders()
        headers.add(name: "Content-Type", value: "application/json")
        headers.add(name: "Content-Length", value: String(body.count))
        headers.add(name: "Connection", value: "close")

        let head = HTTPResponseHead(version: .http1_1, status: .ok, headers: headers)
        channel.write(HTTPServerResponsePart.head(head), promise: nil)
        channel.write(HTTPServerResponsePart.body(.byteBuffer(buffer)), promise: nil)
        channel.writeAndFlush(HTTPServerResponsePart.end(nil)).whenComplete { _ in
            channel.close(promise: nil)
        }
    }
}
