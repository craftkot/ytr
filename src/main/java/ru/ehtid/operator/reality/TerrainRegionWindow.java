package ru.ehtid.operator.reality;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Objects;

/**
 * Owning-thread desired-region registry for a moving camera. Never reads
 * Minecraft chunks or touches GPU memory. Generation tokens prevent an
 * asynchronous old-world compile from being published after a tile leaves
 * and subsequently re-enters the working set at the same coordinates.
 */
public final class TerrainRegionWindow {
    public record Request(TerrainRegionPlanner.Key key,long generation) {}
    public record Update(List<Request> retained,List<Request> requested,
                         List<Request> evicted) {
        public Update {
            retained=List.copyOf(retained);requested=List.copyOf(requested);
            evicted=List.copyOf(evicted);
        }
    }

    private final LinkedHashMap<TerrainRegionPlanner.Key,Request> wanted=new LinkedHashMap<>();
    private final int radius,capacity;
    private long generation;
    // Keep prepared GPU tiles resident while the player only rotates.
    // A half-tile movement is required before changing the working set.
    // Otherwise a rapid 180-degree turn could evict the very alternative
    // that should appear on the returning extraction frame.
    private static final double REPLAN_DISTANCE_SQ=16D*16D;
    private double lastPlanX=Double.NaN,lastPlanZ=Double.NaN;
    public TerrainRegionWindow(int radius,int capacity) {
        if(radius<0 || radius>TerrainRegionPlanner.MAX_RADIUS
                || capacity<1 || capacity>TerrainRegionPlanner.MAX_RESIDENT)
            throw new IllegalArgumentException("Invalid terrain window budget");
        this.radius=radius;this.capacity=capacity;
    }

    public Update update(double x,double z,double yaw) {
        var candidate=TerrainRegionPlanner.plan(x,z,yaw,radius,capacity);
        boolean fixedCamera=!wanted.isEmpty()
                && (x-lastPlanX)*(x-lastPlanX)+(z-lastPlanZ)*(z-lastPlanZ)<REPLAN_DISTANCE_SQ
                && Math.floorDiv((long)Math.floor(x),TerrainRegionPlanner.TILE_SIZE)
                    ==Math.floorDiv((long)Math.floor(lastPlanX),TerrainRegionPlanner.TILE_SIZE)
                && Math.floorDiv((long)Math.floor(z),TerrainRegionPlanner.TILE_SIZE)
                    ==Math.floorDiv((long)Math.floor(lastPlanZ),TerrainRegionPlanner.TILE_SIZE);
        var desired=fixedCamera?List.copyOf(wanted.keySet()):candidate;
        var previous=new LinkedHashMap<>(wanted);
        var retained=new ArrayList<Request>();
        var requested=new ArrayList<Request>();
        var evicted=new ArrayList<Request>();
        wanted.clear();
        for(var key:desired) {
            var live=previous.get(key);
            if(live==null) {
                generation=Math.incrementExact(generation);
                live=new Request(key,generation);
                requested.add(live);
            } else retained.add(live);
            wanted.put(key,live);
        }
        for(var old:previous.values())if(!wanted.containsKey(old.key()))evicted.add(old);
        if(!fixedCamera) {
            lastPlanX=x;
            lastPlanZ=z;
        }
        return new Update(retained,requested,evicted);
    }

    /** Validate on the game/render owning thread before adopting any job. */
    public boolean accepts(Request finished) {
        return finished!=null && Objects.equals(wanted.get(finished.key()),finished);
    }
    public int size(){return wanted.size();}
    public List<Request> snapshot(){return List.copyOf(wanted.values());}
    public void clear(){wanted.clear();lastPlanX=Double.NaN;lastPlanZ=Double.NaN;}
}
