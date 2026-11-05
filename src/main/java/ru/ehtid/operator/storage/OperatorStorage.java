package ru.ehtid.operator.storage;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import ru.ehtid.operator.OperatorMod;
import ru.ehtid.operator.data.OperatorWorldData;

public final class OperatorStorage {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DIRECTORY = "operator_reality";
    private static final String FILE_NAME = "operator-memory.json";

    private OperatorStorage() { }

    public static OperatorWorldData load(MinecraftServer server) {
        Path file = getFile(server);
        if (!Files.exists(file)) {
            return new OperatorWorldData();
        }

        try (Reader reader = Files.newBufferedReader(file)) {
            OperatorWorldData data = GSON.fromJson(reader, OperatorWorldData.class);
            if (data == null) {
                return new OperatorWorldData();
            }
            data.normalize();
            return data;
        } catch (Exception exception) {
            Path backup = file.resolveSibling(FILE_NAME + ".corrupt-backup");
            try {
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
                OperatorMod.LOGGER.warn("A backup of unreadable Operator memory was written to {}", backup);
            } catch (IOException backupException) {
                OperatorMod.LOGGER.error("Could not back up unreadable Operator memory at {}", file, backupException);
            }
            OperatorMod.LOGGER.error("Could not read Operator memory at {}. Starting a fresh memory state.", file, exception);
            return new OperatorWorldData();
        }
    }

    public static boolean save(MinecraftServer server, OperatorWorldData data) {
        Path file = getFile(server);
        Path temporary = file.resolveSibling(FILE_NAME + ".tmp");

        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException exception) {
            OperatorMod.LOGGER.error("Could not save Operator memory to {}", file, exception);
            return false;
        }
    }

    private static Path getFile(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY).resolve(FILE_NAME);
    }
}
