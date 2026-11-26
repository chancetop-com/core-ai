package ai.core.server.report.store;

import ai.core.server.report.domain.HomeReportLayout;
import ai.core.server.report.domain.HomeReportPin;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL storage for the per-user home report layout and pins.
 *
 * @author core-ai
 */
public class PgHomeReportLayoutStore {
    public static final int MAX_PINS = 20;

    private static final String LAYOUT_TABLE = "app.home_report_layouts";
    private static final String PIN_TABLE = "app.home_report_pins";
    private static final String LAYOUT_COLUMNS = "user_id, revision, updated_at";
    private static final String PIN_COLUMNS = "id, user_id, report_id, position, width, created_at, updated_at";

    @Inject
    Database database;

    public void ensure(String userId) {
        database.execute("INSERT INTO " + LAYOUT_TABLE + " (user_id, revision, updated_at) VALUES (?, 0, now()) "
                + "ON CONFLICT (user_id) DO NOTHING", userId);
    }

    public Optional<HomeReportLayout> lock(String userId) {
        return database.selectOne("SELECT " + LAYOUT_COLUMNS + " FROM " + LAYOUT_TABLE + " WHERE user_id = ? FOR UPDATE",
                PgHomeReportLayoutRow.class, userId).map(this::toLayout);
    }

    public Optional<HomeReportLayout> get(String userId) {
        return database.selectOne("SELECT " + LAYOUT_COLUMNS + " FROM " + LAYOUT_TABLE + " WHERE user_id = ?",
                PgHomeReportLayoutRow.class, userId).map(this::toLayout);
    }

    public boolean bumpRevision(String userId, long expectedRevision) {
        return database.execute("UPDATE " + LAYOUT_TABLE + " SET revision = revision + 1, updated_at = now() "
                + "WHERE user_id = ? AND revision = ?", userId, expectedRevision) > 0;
    }

    public List<HomeReportPin> pins(String userId) {
        return database.select("SELECT " + PIN_COLUMNS + " FROM " + PIN_TABLE
                        + " WHERE user_id = ? ORDER BY position, id", PgHomeReportPinRow.class, userId)
                .stream().map(this::toPin).toList();
    }

    public Optional<HomeReportPin> pin(String userId, String pinId) {
        return database.selectOne("SELECT " + PIN_COLUMNS + " FROM " + PIN_TABLE + " WHERE user_id = ? AND id = ?",
                PgHomeReportPinRow.class, userId, pinId).map(this::toPin);
    }

    public Optional<HomeReportPin> pinByReport(String userId, String reportId) {
        return database.selectOne("SELECT " + PIN_COLUMNS + " FROM " + PIN_TABLE + " WHERE user_id = ? AND report_id = ?",
                PgHomeReportPinRow.class, userId, reportId).map(this::toPin);
    }

    public long countPins(String userId) {
        return database.selectOne("SELECT count(*) AS total FROM " + PIN_TABLE + " WHERE user_id = ?",
                ai.core.server.dataset.store.PgCountRow.class, userId).map(row -> row.total).orElse(0L);
    }

    public int nextPosition(String userId) {
        return database.selectOne("SELECT coalesce(max(position), -1) + 1 AS position FROM " + PIN_TABLE + " WHERE user_id = ?",
                PgReportPositionRow.class, userId).map(row -> row.position != null ? row.position : 0).orElse(0);
    }

    public void insertPin(HomeReportPin pin) {
        database.execute("INSERT INTO " + PIN_TABLE + " (id, user_id, report_id, position, width, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                pin.id, pin.userId, pin.reportId, pin.position, pin.width, pin.createdAt, pin.updatedAt);
    }

    public boolean deletePin(String userId, String pinId) {
        return database.execute("DELETE FROM " + PIN_TABLE + " WHERE user_id = ? AND id = ?", userId, pinId) > 0;
    }

    public long deletePinsByReport(String userId, String reportId) {
        return database.execute("DELETE FROM " + PIN_TABLE + " WHERE user_id = ? AND report_id = ?", userId, reportId);
    }

    public List<HomeReportPin> pinsByReport(String reportId) {
        return database.select("SELECT " + PIN_COLUMNS + " FROM " + PIN_TABLE
                        + " WHERE report_id = ? ORDER BY user_id, id", PgHomeReportPinRow.class, reportId)
                .stream().map(this::toPin).toList();
    }

    public boolean updatePosition(String userId, String pinId, int position) {
        return database.execute("UPDATE " + PIN_TABLE + " SET position = ?, updated_at = now() WHERE user_id = ? AND id = ?",
                position, userId, pinId) > 0;
    }

    public boolean updateWidth(String userId, String pinId, String width) {
        return database.execute("UPDATE " + PIN_TABLE + " SET width = ?, updated_at = now() WHERE user_id = ? AND id = ?",
                width, userId, pinId) > 0;
    }

    private HomeReportLayout toLayout(PgHomeReportLayoutRow row) {
        var layout = new HomeReportLayout();
        layout.userId = row.userId;
        layout.revision = row.revision != null ? row.revision : 0;
        layout.updatedAt = row.updatedAt;
        return layout;
    }

    private HomeReportPin toPin(PgHomeReportPinRow row) {
        var pin = new HomeReportPin();
        pin.id = row.id;
        pin.userId = row.userId;
        pin.reportId = row.reportId;
        pin.position = row.position != null ? row.position : 0;
        pin.width = row.width;
        pin.createdAt = row.createdAt;
        pin.updatedAt = row.updatedAt;
        return pin;
    }
}
