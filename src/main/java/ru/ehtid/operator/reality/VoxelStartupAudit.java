package ru.ehtid.operator.reality;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only server-startup inventory of one world-local voxel WAL directory.
 * A damaged journal, unexpected file, symlink, mismatched world identity or
 * excess records FAILS THE WHOLE SCAN: it cannot be ignored or auto-deleted.
 *
 * An unresolved PREPARED/APPLIED intent blocks *new* voxel transactions.
 * Recovery is deliberately manual and must pass a separate world safety
 * check; this scan never opens chunks or writes blocks.
 */
public final class VoxelStartupAudit {
    public static final int MAX_JOURNALS=128;
    public record Scan(int total,int resolved,List<VoxelWriteAheadLog.Entry> pending) {
        public Scan {
            if(total<0 || resolved<0 || resolved>total)
                throw new IllegalArgumentException("Invalid journal inventory");
            pending=List.copyOf(pending);
            if(resolved+pending.size()!=total)
                throw new IllegalArgumentException("Incomplete journal inventory");
        }
        public boolean blocksNewWrites(){return !pending.isEmpty();}
        public String summary(){
            return total+" voxel WALs; "+resolved+" resolved; "+pending.size()
                    +" pending; new writes "+(blocksNewWrites()?"BLOCKED":"allowed");
        }
    }
    private VoxelStartupAudit(){}

    public static Scan scan(Path directory,String dimension) throws IOException {
        Objects.requireNonNull(directory);
        Objects.requireNonNull(dimension);
        if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))
            return new Scan(0,0,List.of());
        if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(directory))
            throw new IOException("Unsafe voxel journal directory");
        var pending=new ArrayList<VoxelWriteAheadLog.Entry>();
        int total=0,resolved=0;
        try(DirectoryStream<Path> files=Files.newDirectoryStream(directory)) {
            for(var file:files) {
                if(++total>MAX_JOURNALS)
                    throw new IOException("Voxel WAL inventory exceeds budget");
                String name=file.getFileName().toString();
                if(!name.endsWith(".vwal") || !Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(file))
                    throw new IOException("Unexpected or unsafe voxel WAL file");
                String stem=name.substring(0,name.length()-5);
                UUID id;
                try {
                    id=UUID.fromString(stem);
                    if(!id.toString().equals(stem))
                        throw new IllegalArgumentException("Noncanonical WAL UUID");
                } catch(IllegalArgumentException invalid) {
                    throw new IOException("Malformed voxel WAL file name",invalid);
                }
                var entry=VoxelWriteAheadLog.inspect(directory,id);
                if(!dimension.equals(entry.dimension()))
                    throw new IOException("Voxel WAL belongs to a different dimension");
                if(entry.phase()==VoxelWriteAheadLog.Phase.RESOLVED)resolved++;
                else pending.add(entry);
            }
        }
        pending.sort(Comparator.comparing(e->e.id().toString()));
        return new Scan(total,resolved,pending);
    }
}
