package ai.core.cli.command;

public record HandlerContext(ReplCommandHandler commands, MemoryCommandHandler memoryCommand, boolean memoryEnabled) {
}
