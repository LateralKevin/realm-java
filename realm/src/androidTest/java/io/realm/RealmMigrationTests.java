package io.realm;

import android.test.AndroidTestCase;

import java.io.File;
import java.io.IOException;

import io.realm.entities.AllTypes;
import io.realm.entities.FieldOrder;
import io.realm.entities.AnnotationTypes;
import io.realm.exceptions.RealmMigrationNeededException;
import io.realm.internal.ColumnType;
import io.realm.internal.Table;

public class RealmMigrationTests extends AndroidTestCase {

    public Realm realm;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        Realm.deleteRealmFile(getContext());
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        if (realm != null) {
            realm.close();
        }
        Realm.setSchema(null);
    }

    public void testRealmClosedAfterMigrationException() throws IOException {
        String REALM_NAME = "default0.realm";
        Realm.deleteRealmFile(getContext(), REALM_NAME);
        TestHelper.copyRealmFromAssets(getContext(), REALM_NAME, REALM_NAME);
        try {
            Realm.getInstance(getContext(), REALM_NAME);
            fail("A migration should be triggered");
        } catch (RealmMigrationNeededException expected) {
            Realm.deleteRealmFile(getContext(), REALM_NAME); // Delete old realm
        }

        // This should recreate the Realm with proper schema
        Realm realm = Realm.getInstance(getContext(), REALM_NAME);
        int result = realm.where(AllTypes.class).equalTo("columnString", "Foo").findAll().size();
        assertEquals(0, result);
    }

    // If a migration creates a different ordering of columns on Realm A, while another ordering is generated by
    // creating a new Realm B. Global column indices will not work. They must be calculated for each Realm.
    public void testLocalColumnIndices() throws IOException {
        String MIGRATED_REALM = "migrated.realm";
        String NEW_REALM = "new.realm";

        // Migrate old Realm to proper schema
        Realm.deleteRealmFile(getContext(), MIGRATED_REALM);
        Realm.setSchema(AllTypes.class);
        Realm migratedRealm = Realm.getInstance(getContext(), MIGRATED_REALM);
        migratedRealm.close();
        Realm.migrateRealmAtPath(new File(getContext().getFilesDir(), MIGRATED_REALM).getAbsolutePath(), new RealmMigration() {
            @Override
            public long execute(Realm realm, long version) {
                Table languageTable = realm.getTable(FieldOrder.class);
                if (languageTable.getColumnCount() == 0) {
                    languageTable.addColumn(ColumnType.INTEGER, "field2");
                    languageTable.addColumn(ColumnType.BOOLEAN, "field1");
                }

                return version + 1;
            }
        });

        // Open migrated Realm and populate column indices based on migration ordering.
        Realm.setSchema(AllTypes.class, FieldOrder.class);
        migratedRealm = Realm.getInstance(getContext(), MIGRATED_REALM);

        // Create new Realm which will cause column indices to be recalculated based on the order in the java file
        // instead of the migration
        Realm.deleteRealmFile(getContext(), NEW_REALM);
        Realm newRealm = Realm.getInstance(getContext(), NEW_REALM);
        newRealm.close();

        // Try to query migrated realm. With local column indices this will work. With global it will fail.
        assertEquals(0, migratedRealm.where(FieldOrder.class).equalTo("field1", true).findAll().size());
    }

    public void testNotSettingIndexThrows() {
        Realm.setSchema(AnnotationTypes.class);
        Realm.migrateRealmAtPath(new File(getContext().getFilesDir(), "default.realm").getAbsolutePath(), new RealmMigration() {
            @Override
            public long execute(Realm realm, long version) {
                Table table = realm.getTable(AnnotationTypes.class);
                table.addColumn(ColumnType.INTEGER, "id");
                table.setPrimaryKey("id");
                table.addColumn(ColumnType.STRING, "indexString");
                table.addColumn(ColumnType.STRING, "notIndexString");
                // Forget to set @Index
                return 1;
            }
        });

        try {
            realm = Realm.getInstance(getContext());
            fail();
        } catch (RealmMigrationNeededException expected) {
        }
    }

    public void testNotSettingPrimaryKeyThrows() {
        Realm.setSchema(AnnotationTypes.class);
        Realm.migrateRealmAtPath(new File(getContext().getFilesDir(), "default.realm").getAbsolutePath(), new RealmMigration() {
            @Override
            public long execute(Realm realm, long version) {
                Table table = realm.getTable(AnnotationTypes.class);
                table.addColumn(ColumnType.INTEGER, "id");
                // Forget to set @PrimaryKey
                long columnIndex = table.addColumn(ColumnType.STRING, "indexString");
                table.addSearchIndex(columnIndex);
                table.addColumn(ColumnType.STRING, "notIndexString");
                return 1;
            }
        });

        try {
            realm = Realm.getInstance(getContext());
            fail();
        } catch (RealmMigrationNeededException expected) {
        }
    }

    public void testSetAnnotations() {
        Realm.setSchema(AnnotationTypes.class);
        Realm.migrateRealmAtPath(new File(getContext().getFilesDir(), "default.realm").getAbsolutePath(), new RealmMigration() {
            @Override
            public long execute(Realm realm, long version) {
                Table table = realm.getTable(AnnotationTypes.class);
                table.addColumn(ColumnType.INTEGER, "id");
                table.setPrimaryKey("id");
                long columnIndex = table.addColumn(ColumnType.STRING, "indexString");
                table.addSearchIndex(columnIndex);
                table.addColumn(ColumnType.STRING, "notIndexString");
                return 1;
            }
        });

        realm = Realm.getInstance(getContext());
        Table table = realm.getTable(AnnotationTypes.class);
        assertEquals(3, table.getColumnCount());
        assertTrue(table.hasPrimaryKey());
        assertTrue(table.hasSearchIndex(table.getColumnIndex("indexString")));
    }

    public void testGetPathFromMigrationException() throws IOException {
        TestHelper.copyRealmFromAssets(getContext(), "default0.realm", Realm.DEFAULT_REALM_NAME);
        File realm = new File(getContext().getFilesDir(), Realm.DEFAULT_REALM_NAME);
        try {
            Realm.getInstance(getContext());
            fail();
        } catch (RealmMigrationNeededException expected) {
            assertEquals(expected.getPath(), realm.getCanonicalPath());
        }
    }
}
