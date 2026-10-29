package com.retail.oms.inventory.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** location_snapshot: the newest snapshot applied per location. */
@Repository
public class SnapshotState {

    private final JdbcTemplate jdbc;

    public SnapshotState(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records this snapshot as the newest for the location, unless a newer one was applied already. The row stays
     * locked until the transaction ends, so two snapshots for one location are applied one after the other.
     *
     * @return false if the snapshot is older than (or as old as) the last applied one: ignore it
     */
    public boolean claimIfNewer(String locationId, String snapshotId, Instant takenAt) {
        return jdbc.update("""
                INSERT INTO location_snapshot (location_id, snapshot_id, taken_at, applied_at) VALUES (?, ?, ?, now())
                ON CONFLICT (location_id) DO UPDATE
                   SET snapshot_id = EXCLUDED.snapshot_id, taken_at = EXCLUDED.taken_at, applied_at = now()
                 WHERE location_snapshot.taken_at < EXCLUDED.taken_at""",
                locationId, snapshotId, Timestamp.from(takenAt)) == 1;
    }
}
