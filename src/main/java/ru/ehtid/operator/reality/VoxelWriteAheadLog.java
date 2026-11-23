package ru.ehtid.operator.reality;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * World-local write-ahead intent for a SINGLE 16^3 stone/air section.
 *
 * A PREPARED record is durably written before the first physical block
 * write. The full before/after snapshot, section address, dimension binding
 * and revision are checksummed. A crash between any two block writes leaves
 * enough information to recover the original blocks, without relying on an
 * in-memory transition object.
 *
 * Calling this code never authorizes a world mutation: a separate owner-
 * thread safety gate, loaded chunk check and strict recovery validator are
 * required. This journal format does not support arbitrary BlockState yet.
 */
public final class VoxelWriteAheadLog {
    private static final int MAGIC=0x45564854; // EVHT
    private static final int VERSION=1;
    private static final int HASH_BYTES=32;
    public static final int MAX_FILE_BYTES=16*1024;
    private static final int COUNT=VoxelSectionSurface.VOLUME;

    public enum Phase { PREPARED, APPLIED, RESOLVED }

    public record Entry(UUID id,String dimension,SectionTerrainMesh.Key section,
                        long revision,Phase phase,byte[] before,byte[] after) {
        public Entry {
            Objects.requireNonNull(id);
            Objects.requireNonNull(dimension);
            Objects.requireNonNull(section);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(before);
            Objects.requireNonNull(after);
            if(!dimension.matches("[a-z0-9._:/-]{1,128}")
                    || revision<=0 || before.length!=COUNT || after.length!=COUNT)
                throw new IllegalArgumentException("Invalid voxel intent metadata");
            Math.multiplyExact(section.x(),16);
            Math.multiplyExact(section.y(),16);
            Math.multiplyExact(section.z(),16);
            before=before.clone();
            after=after.clone();
            int mutations=0;
            for(int i=0;i<COUNT;i++) {
                byte a=before[i],b=after[i];
                if((a!=VoxelSectionSurface.AIR && a!=VoxelSectionSurface.STONE)
                        || (b!=VoxelSectionSurface.AIR && b!=VoxelSectionSurface.STONE))
                    throw new IllegalArgumentException("Unsupported block in durable intent");
                if(a==b)continue;
                int x=i%16,z=(i/16)%16,y=i/256;
                if(x==0||x==15||y==0||y==15||z==0||z==15)
                    throw new IllegalArgumentException("Journal would break neighbor-section seams");
                mutations++;
                if(mutations>VoxelSectionTransition.MAX_CHANGES)
                    throw new IllegalArgumentException("Journal mutation budget exceeded");
            }
            if(mutations==0)throw new IllegalArgumentException("No voxel journal changes");
        }
        @Override public byte[] before(){return before.clone();}
        @Override public byte[] after(){return after.clone();}
        public int changedCount() {
            int changes=0;
            for(int i=0;i<COUNT;i++)if(before[i]!=after[i])changes++;
            return changes;
        }
        public Entry withPhase(Phase next) {
            return new Entry(id,dimension,section,revision,next,before,after);
        }
        public boolean samePlan(Entry that) {
            return that!=null && id.equals(that.id) && dimension.equals(that.dimension)
                    && section.equals(that.section) && revision==that.revision
                    && Arrays.equals(before,that.before) && Arrays.equals(after,that.after);
        }
    }

    private VoxelWriteAheadLog() {}

    public static Entry prepare(UUID id,String dimension,SectionTerrainMesh.Key section,
                                VoxelSectionTransition transition) {
        Objects.requireNonNull(transition);
        return new Entry(id,dimension,section,transition.revision(),Phase.PREPARED,
                transition.originalSnapshot(),transition.alternateSnapshot());
    }

    public static Path path(Path directory,UUID id) {
        Objects.requireNonNull(directory);
        return directory.resolve(Objects.requireNonNull(id)+".vwal");
    }

    public static void create(Path directory,Entry intent) throws IOException {
        Objects.requireNonNull(intent);
        if(intent.phase()!=Phase.PREPARED)
            throw new IOException("New journal must be PREPARED");
        write(path(directory,intent.id()),intent,false);
    }

    /** Read-only entry inspection for an audit of the exact world-local
     * journal directory. This does not authorize mutation. */
    public static Entry inspect(Path directory,UUID id) throws IOException {
        Entry entry=read(path(directory,id));
        if(!entry.id().equals(id))
            throw new IOException("Voxel WAL filename does not match persisted UUID");
        return entry;
    }

    public static Entry load(Path directory,UUID id,String expectedDimension,
                             SectionTerrainMesh.Key expectedSection) throws IOException {
        Entry entry=inspect(directory,id);
        if(!entry.id().equals(id) || !entry.dimension().equals(expectedDimension)
                || !entry.section().equals(expectedSection))
            throw new IOException("Voxel journal does not belong to this world and section");
        return entry;
    }

    /** Caller must serialize updates on the same owning server thread. */
    public static Entry advance(Path directory,Entry expected,Phase next) throws IOException {
        if(!((expected.phase()==Phase.PREPARED && next==Phase.APPLIED)
                || ((expected.phase()==Phase.PREPARED || expected.phase()==Phase.APPLIED)
                        && next==Phase.RESOLVED)))
            throw new IOException("Illegal voxel WAL phase transition");
        Entry current=load(directory,expected.id(),expected.dimension(),expected.section());
        if(current.phase()!=expected.phase() || !current.samePlan(expected))
            throw new IOException("Voxel WAL changed since it was loaded");
        Entry updated=current.withPhase(next);
        write(path(directory,current.id()),updated,true);
        return updated;
    }

    private static byte[] checksum(byte[] data) {
        try {return MessageDigest.getInstance("SHA-256").digest(data);}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    private static byte[] encode(Entry entry) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream(MAX_FILE_BYTES);
        try(DataOutputStream data=new DataOutputStream(out)) {
            data.writeInt(MAGIC);
            data.writeInt(VERSION);
            data.writeLong(entry.id().getMostSignificantBits());
            data.writeLong(entry.id().getLeastSignificantBits());
            data.writeUTF(entry.dimension());
            data.writeInt(entry.section().x());
            data.writeInt(entry.section().y());
            data.writeInt(entry.section().z());
            data.writeLong(entry.revision());
            data.writeByte(entry.phase().ordinal());
            data.write(entry.before());
            data.write(entry.after());
            data.flush();
            data.write(checksum(out.toByteArray()));
        }
        byte[] bytes=out.toByteArray();
        if(bytes.length>MAX_FILE_BYTES)throw new IOException("Voxel WAL oversized");
        return bytes;
    }

    private static Entry read(Path file) throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file) || Files.size(file)>MAX_FILE_BYTES)
            throw new IOException("Voxel WAL missing, symlink or oversized");
        byte[] bytes=Files.readAllBytes(file);
        if(bytes.length<HASH_BYTES+64 || bytes.length>MAX_FILE_BYTES)
            throw new IOException("Truncated voxel WAL");
        int length=bytes.length-HASH_BYTES;
        byte[] digest=Arrays.copyOfRange(bytes,length,bytes.length);
        if(!MessageDigest.isEqual(digest,checksum(Arrays.copyOf(bytes,length))))
            throw new IOException("Voxel WAL checksum mismatch");
        try(DataInputStream in=new DataInputStream(
                new ByteArrayInputStream(bytes,0,length))) {
            if(in.readInt()!=MAGIC || in.readInt()!=VERSION)
                throw new IOException("Unsupported voxel WAL version");
            UUID id=new UUID(in.readLong(),in.readLong());
            String dimension=in.readUTF();
            var section=new SectionTerrainMesh.Key(in.readInt(),in.readInt(),in.readInt());
            long revision=in.readLong();
            int phase=in.readUnsignedByte();
            if(phase>=Phase.values().length)throw new IOException("Unknown WAL phase");
            byte[] before=new byte[COUNT],after=new byte[COUNT];
            in.readFully(before);
            in.readFully(after);
            if(in.available()!=0)throw new IOException("Trailing voxel WAL payload");
            return new Entry(id,dimension,section,revision,
                    Phase.values()[phase],before,after);
        } catch(RuntimeException invalid) {
            throw new IOException("Invalid voxel WAL content",invalid);
        }
    }

    private static void write(Path destination,Entry entry,boolean replace)
            throws IOException {
        Path directory=destination.getParent();
        Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory))
            throw new IOException("Refuse symlinked voxel journal directory");
        if(replace ? !Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS)
                : Files.exists(destination,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Voxel WAL create/replace mismatch");
        byte[] bytes=encode(entry);
        Path temp=Files.createTempFile(directory,"voxel-",".tmp");
        try {
            try(FileChannel output=FileChannel.open(temp,StandardOpenOption.WRITE)) {
                ByteBuffer source=ByteBuffer.wrap(bytes);
                while(source.hasRemaining())output.write(source);
                output.force(true);
            }
            if(replace) Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            else Files.move(temp,destination,StandardCopyOption.ATOMIC_MOVE);
            // Directory fsync is best effort on platforms that support it.
            try(FileChannel dir=FileChannel.open(directory,StandardOpenOption.READ)) {
                dir.force(true);
            } catch(IOException | UnsupportedOperationException unsupported) {
                // Do not report a guarantee of OS-level directory durability.
            }
        } finally {Files.deleteIfExists(temp);}
    }
}
