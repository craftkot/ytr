package ru.ehtid.operator.reality;

import java.nio.IntBuffer;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionTerrainMeshTest {
    private static PreparedTerrainMesh pair(int[] h,int edge) {
        int[] border=new int[32];Arrays.fill(border,edge);
        return PreparedTerrainMesh.compile(
            PreparedTerrainSurface.prepare(-32,0,913,1,-64,320,h),
            new PreparedTerrainMesh.Halo(border,border,border,border));
    }
    private static long area(IntBuffer v) {
        int[] q=new int[12];long total=0;
        while(v.hasRemaining()) {
            v.get(q);long ax=q[3]-q[0],ay=q[4]-q[1],az=q[5]-q[2];
            long bx=q[6]-q[0],by=q[7]-q[1],bz=q[8]-q[2];
            total+=Math.abs(ay*bz-az*by)+Math.abs(az*bx-ax*bz)+Math.abs(ax*by-ay*bx);
        }
        return total;
    }
    @Test void flatTileAtExactNegativeHeightOwnsFourSectionsBelowPlane() {
        int[] h=new int[1024];Arrays.fill(h,-16);
        var split=SectionTerrainMesh.split(pair(h,-16),false);
        assertEquals(4,split.size());
        for(var e:split.entrySet()) {
            assertEquals(-2,e.getKey().y());
            assertTrue(e.getKey().x()==-2||e.getKey().x()==-1);
            assertEquals(256,e.getValue().quadCount());
            assertTrue(e.getValue().vertices().isReadOnly());
        }
        assertThrows(UnsupportedOperationException.class,split::clear);
    }
    @Test void verticalWallsCrossNegativeAndPositiveSectionsWithoutLosingArea() {
        int[] h=new int[1024];
        for(int z=0;z<32;z++) for(int x=0;x<32;x++)
            h[z*32+x]=x<16?49:-17;
        var pair=pair(h,-17);
        for(boolean alternate:new boolean[]{false,true}) {
            var map=SectionTerrainMesh.split(pair,alternate);
            long original=area((alternate?pair.alternate():pair.original()).vertices());
            long partitioned=0;
            for(var e:map.entrySet()) {
                var key=e.getKey();
                var data=e.getValue().vertices();
                partitioned+=area(data.duplicate());
                while(data.hasRemaining()) {
                    int worldX=data.get()-32,y=data.get(),worldZ=data.get();
                    assertTrue(worldX>=key.x()*16&&worldX<=(key.x()+1)*16);
                    assertTrue(y>=key.y()*16&&y<=(key.y()+1)*16);
                    assertTrue(worldZ>=key.z()*16&&worldZ<=(key.z()+1)*16);
                }
            }
            assertEquals(original,partitioned);
            assertTrue(map.containsKey(new SectionTerrainMesh.Key(-2,0,0)));
            assertFalse(map.containsKey(new SectionTerrainMesh.Key(-1,0,0)),
                    "East-facing wall at x=-16 belongs to higher west column");
        }
    }
}
