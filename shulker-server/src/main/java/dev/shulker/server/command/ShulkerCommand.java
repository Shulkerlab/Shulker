package dev.shulker.server.command;

import dev.shulker.api.world.ShulkWorldLoader;
import dev.shulker.api.world.ShulkWorldProperties;
import dev.shulker.format.ShulkWorld;
import dev.shulker.format.ShulkWorldFolders;
import dev.shulker.server.world.ShulkWorldManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.jspecify.annotations.Nullable;

/**
 * {@code /shulker world ...}: manage Shulk Worlds from the console or in game.
 *
 * <pre>
 * /shulker world list
 * /shulker world info &lt;world&gt;
 * /shulker world load &lt;name&gt; [loader] [read-only]
 * /shulker world create &lt;name&gt; &lt;template&gt; [loader]
 * /shulker world save &lt;world&gt;
 * /shulker world unload &lt;world&gt; [no-save]
 * /shulker world capture &lt;world&gt; &lt;template&gt;
 * /shulker world tp &lt;world&gt;
 * /shulker world import &lt;folder&gt; &lt;name&gt;
 * /shulker world export &lt;name&gt; &lt;folder&gt; [shulk|mca]
 * </pre>
 *
 * <p>Templates and imported worlds are stored with the file loader ({@code shulker-worlds/}).
 */
public final class ShulkerCommand extends Command {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getClassLogger();

    public static final String LABEL = "shulker";
    public static final String PERMISSION = "shulker.command.world";
    private static final List<String> SUBCOMMANDS = List.of("list", "info", "load", "create", "save", "unload", "capture", "tp", "import", "export");

    public ShulkerCommand() {
        super(LABEL);
        this.description = "Shulker commands";
        this.usageMessage = "/shulker world <" + String.join("|", SUBCOMMANDS) + ">";
        this.setPermission(PERMISSION);
        if (Bukkit.getPluginManager().getPermission(PERMISSION) == null) {
            Bukkit.getPluginManager().addPermission(new Permission(PERMISSION, PermissionDefault.OP));
        }
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (!this.testPermission(sender)) {
            return true;
        }
        if (args.length < 2 || !args[0].equalsIgnoreCase("world")) {
            sender.sendMessage(Component.text("Usage: " + this.usageMessage, NamedTextColor.RED));
            return true;
        }
        final ShulkWorldManager worlds = ShulkWorldManager.get();
        final String sub = args[1].toLowerCase(Locale.ROOT);
        final String[] rest = java.util.Arrays.copyOfRange(args, 2, args.length);
        try {
            switch (sub) {
                case "list" -> this.list(sender, worlds);
                case "info" -> this.info(sender, worlds, this.world(worlds, arg(rest, 0, "world")));
                case "load" -> {
                    final ShulkWorldLoader loader = this.loader(worlds, rest.length > 1 ? rest[1] : "file");
                    final boolean readOnly = rest.length > 2 && rest[2].equalsIgnoreCase("read-only");
                    final ShulkWorldProperties properties = readOnly ? ShulkWorldProperties.readOnlyWorld() : ShulkWorldProperties.defaults();
                    final long start = System.nanoTime();
                    report(sender, worlds.load(loader, arg(rest, 0, "name"), properties), world -> "Loaded " + worlds.name(world) + " in " + millis(start));
                }
                case "create" -> {
                    final String name = arg(rest, 0, "name");
                    final String template = arg(rest, 1, "template");
                    final ShulkWorldLoader saveTo = this.loader(worlds, rest.length > 2 ? rest[2] : "file");
                    final long start = System.nanoTime();
                    report(sender, worlds.readTemplate(worlds.fileLoader(), template)
                        .thenCompose(t -> worlds.create(name, t, saveTo, ShulkWorldProperties.defaults())),
                        world -> "Created " + worlds.name(world) + " from " + template + " in " + millis(start));
                }
                case "save" -> {
                    final World world = this.world(worlds, arg(rest, 0, "world"));
                    report(sender, worlds.save(world), done -> "Saved " + rest[0]);
                }
                case "unload" -> {
                    final World world = this.world(worlds, arg(rest, 0, "world"));
                    final boolean save = !(rest.length > 1 && rest[1].equalsIgnoreCase("no-save"));
                    report(sender, worlds.unload(world, save), done -> "Unloaded " + rest[0] + (save ? "" : " without saving"));
                }
                case "capture" -> {
                    final World world = this.world(worlds, arg(rest, 0, "world"));
                    final String template = arg(rest, 1, "template");
                    report(sender, worlds.serialize(world).thenCompose(bytes -> worlds.fileLoader().write(template, bytes)),
                        done -> "Saved " + rest[0] + " as template " + template);
                }
                case "tp" -> {
                    if (!(sender instanceof Player player)) {
                        throw new IllegalArgumentException("Only players can teleport");
                    }
                    final World world = this.world(worlds, arg(rest, 0, "world"));
                    player.teleportAsync(world.getSpawnLocation());
                }
                case "import" -> {
                    final Path folder = Path.of(arg(rest, 0, "folder"));
                    final String name = arg(rest, 1, "name");
                    report(sender, CompletableFuture.supplyAsync(() -> {
                        try {
                            return ShulkWorldFolders.importFolder(folder).toBytes();
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }).thenCompose(bytes -> worlds.fileLoader().write(name, bytes).thenApply(done -> bytes.length)),
                        size -> "Imported " + folder + " as " + name + " (" + kib(size) + ")");
                }
                case "export" -> {
                    final String name = arg(rest, 0, "name");
                    final Path folder = Path.of(arg(rest, 1, "folder"));
                    final ShulkWorldFolders.RegionFormat format = rest.length > 2 && rest[2].equalsIgnoreCase("mca")
                        ? ShulkWorldFolders.RegionFormat.MCA : ShulkWorldFolders.RegionFormat.SHULK;
                    report(sender, worlds.fileLoader().read(name).thenApply(bytes -> {
                        try {
                            ShulkWorldFolders.exportFolder(ShulkWorld.fromBytes(bytes), folder, format);
                            return folder;
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    }), out -> "Exported " + name + " to " + out);
                }
                default -> sender.sendMessage(Component.text("Usage: " + this.usageMessage, NamedTextColor.RED));
            }
        } catch (IllegalArgumentException | IllegalStateException e) {
            sender.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }

    private void list(final CommandSender sender, final ShulkWorldManager worlds) {
        final List<String> loaded = worlds.worlds().stream().map(worlds::name).filter(java.util.Objects::nonNull).sorted().toList();
        sender.sendMessage(Component.text("Loaded Shulk Worlds (" + loaded.size() + "): " + (loaded.isEmpty() ? "none" : String.join(", ", loaded)), NamedTextColor.AQUA));
        report(sender, worlds.fileLoader().list(), stored -> "Stored in " + worlds.fileFolder() + " (" + stored.size() + "): "
            + (stored.isEmpty() ? "none" : String.join(", ", stored)));
    }

    private void info(final CommandSender sender, final ShulkWorldManager worlds, final World world) {
        final ShulkWorldProperties properties = worlds.properties(world);
        final ShulkWorldManager.Info info = worlds.info(world);
        sender.sendMessage(Component.text(worlds.name(world) + " (dimension shulker:" + worlds.name(world) + "): " + info.chunks() + " chunks, " + kib(info.bytes()) + " in memory, "
            + world.getPlayers().size() + " players, loader " + info.loader()
            + (properties != null && properties.readOnly() ? ", read-only" : "")
            + (info.unsaved() ? ", unsaved changes" : ""), NamedTextColor.AQUA));
    }

    private World world(final ShulkWorldManager worlds, final String name) {
        final @Nullable World world = worlds.world(name);
        if (world == null) {
            throw new IllegalArgumentException(name + " is not a loaded Shulk World");
        }
        return world;
    }

    private ShulkWorldLoader loader(final ShulkWorldManager worlds, final String id) {
        final @Nullable ShulkWorldLoader loader = worlds.loader(id);
        if (loader == null) {
            throw new IllegalArgumentException("No loader '" + id + "' (registered: " + String.join(", ", worlds.loaderIds()) + ")");
        }
        return loader;
    }

    private static String arg(final String[] args, final int index, final String name) {
        if (args.length <= index) {
            throw new IllegalArgumentException("Missing <" + name + ">");
        }
        return args[index];
    }

    private static <T> void report(final CommandSender sender, final CompletableFuture<T> future, final java.util.function.Function<T, String> message) {
        future.whenComplete((result, error) -> {
            if (error != null) {
                LOGGER.error("/shulker world failed", ShulkWorldManager.unwrap(error));
            }
            final Component text = error == null
                ? Component.text(message.apply(result), NamedTextColor.GREEN)
                : Component.text(String.valueOf(ShulkWorldManager.unwrap(error).getMessage()), NamedTextColor.RED);
            sender.sendMessage(text);
        });
    }

    private static String millis(final long startNanos) {
        return String.format(Locale.ROOT, "%.1f ms", (System.nanoTime() - startNanos) / 1_000_000.0);
    }

    private static String kib(final long bytes) {
        return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }

    @Override
    public List<String> tabComplete(final CommandSender sender, final String alias, final String[] args) {
        if (args.length == 1) {
            return Stream.of("world").filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("world")) {
            return SUBCOMMANDS.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("world")) {
            final ShulkWorldManager worlds = ShulkWorldManager.get();
            final List<String> names = new ArrayList<>();
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "info", "save", "unload", "capture", "tp" -> worlds.worlds().forEach(world -> names.add(worlds.name(world)));
                default -> {
                }
            }
            return names.stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).toList();
        }
        return List.of();
    }
}
