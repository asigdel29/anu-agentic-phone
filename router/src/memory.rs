//! `memory.rs`: the horizon, in public SQL.
//!
//! History
//!   2026-08-08  A. Sigdel  Created.
//!
//! Contents
//!   `Error`    Why the horizon could not be applied.
//!   `Moved`    What it moved.
//!   `apply`    Move everything past the horizon into the archive.
//!
//! `zeromem` loads every turn at open and indexes each one, so a phone pays for
//! all of its history at launch. Run before `ZeroMem::open`, this moves what is
//! past the horizon into an archive table in the same file, and zeromem then
//! sees a bounded `turns`. `docs/decisions/memory-on-a-phone-forgets.md` argues
//! why this rather than a fork.
//!
//! Bounding `turns` alone would be half of it. zeromem caches one embedding per
//! turn in a separate table, keyed by the id with nothing joining the two, so
//! archiving a turn and leaving its vector bounds the cheap table and leaves the
//! expensive one growing. At 256 floats a turn, the vectors are the storage.
//!
//! One transaction: a crash between the insert and the delete either duplicates
//! every archived turn or loses it, and this runs at launch on a phone.

use rusqlite::Connection;
use std::path::Path;

/// Why the horizon could not be applied.
#[derive(Debug, thiserror::Error)]
pub enum Error {
    /// The database could not be opened or written.
    #[error("memory store at {path}: {detail}")]
    Store {
        /// Where it looked, and what SQLite said.
        path: String,
        /// What SQLite said.
        detail: String,
    },
}

/// What the horizon moved.
#[derive(Debug, Default, PartialEq, Eq, Clone, Copy)]
pub struct Moved {
    /// Turns moved into the archive.
    pub turns: usize,
    /// Their cached embeddings, moved with them.
    pub embeddings: usize,
}

/// The schema the archive needs, which zeromem does not know about.
///
/// Separate tables rather than a flag on the live ones, which would still be
/// loaded by `SELECT ... FROM turns`, which is the whole thing being avoided.
const ARCHIVE: &str = "
    CREATE TABLE IF NOT EXISTS archived_turns (
        id INTEGER PRIMARY KEY,
        session_id TEXT NOT NULL,
        session_turn INTEGER NOT NULL,
        speaker TEXT NOT NULL,
        text TEXT NOT NULL,
        ts INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS archived_embeddings (
        kind TEXT NOT NULL,
        key TEXT NOT NULL,
        vec BLOB NOT NULL,
        PRIMARY KEY (kind, key)
    );";

/// Move everything past the horizon into the archive.
///
/// # Arguments
/// * `path`: the memory database, WHERE it may not exist yet.
/// * `keep`: how many of the most recent turns `zeromem` should still see.
///
/// # Returns
/// What moved, and `Moved::default()` when there was nothing to do: no database
/// or fewer turns than the horizon, both ordinary.
///
/// # Errors
/// [`Error::Store`] IF the database exists and cannot be opened or written.
///
/// # Atomic
/// One transaction. Turns and their embeddings move together or not at all.
pub fn apply(path: &Path, keep: usize) -> Result<Moved, Error> {
    // A first run has no database, and creating one here would leave zeromem's
    // own `CREATE TABLE IF NOT EXISTS` to fix a file it did not make.
    if !path.exists() {
        return Ok(Moved::default());
    }

    let mut conn = Connection::open(path).map_err(|why| store(path, &why))?;
    conn.execute_batch(ARCHIVE)
        .map_err(|why| store(path, &why))?;

    let tx = conn.transaction().map_err(|why| store(path, &why))?;
    let moved = move_past(&tx, keep).map_err(|why| store(path, &why))?;
    tx.commit().map_err(|why| store(path, &why))?;
    Ok(moved)
}

/// The move itself, inside a transaction.
fn move_past(tx: &rusqlite::Transaction<'_>, keep: usize) -> rusqlite::Result<Moved> {
    // The oldest turn zeromem should still see. Nothing to do when there are not
    // that many, which is the ordinary state for a long time.
    let Some(oldest_kept) = newest_nth(tx, keep)? else {
        return Ok(Moved::default());
    };

    // Embeddings first: they are selected by the ids still in `turns`, so moving
    // the turns first would leave nothing to find them by.
    let embeddings = tx.execute(
        "INSERT OR REPLACE INTO archived_embeddings (kind, key, vec)
         SELECT kind, key, vec FROM embeddings
         WHERE kind = 'turn' AND CAST(key AS INTEGER) < ?1",
        [oldest_kept],
    )?;
    tx.execute(
        "DELETE FROM embeddings WHERE kind = 'turn' AND CAST(key AS INTEGER) < ?1",
        [oldest_kept],
    )?;

    let turns = tx.execute(
        "INSERT OR REPLACE INTO archived_turns
         SELECT id, session_id, session_turn, speaker, text, ts FROM turns WHERE id < ?1",
        [oldest_kept],
    )?;
    tx.execute("DELETE FROM turns WHERE id < ?1", [oldest_kept])?;

    Ok(Moved { turns, embeddings })
}

/// The id of the `keep`th most recent turn, or `None` when there are fewer.
///
/// Zero archives everything, which is how a store is emptied without deleting it
/// rather than a case to reject.
fn newest_nth(tx: &rusqlite::Transaction<'_>, keep: usize) -> rusqlite::Result<Option<i64>> {
    if keep == 0 {
        // One past the largest id, so every row is strictly below it. `MAX + 1`
        // rather than `MAX`, which would keep the newest turn.
        return tx.query_row("SELECT MAX(id) + 1 FROM turns", [], |row| row.get(0));
    }
    tx.query_row(
        "SELECT id FROM turns ORDER BY id DESC LIMIT 1 OFFSET ?1",
        [keep - 1],
        |row| row.get(0),
    )
    .map(Some)
    .or_else(|why| match why {
        // Fewer turns than the horizon. Ordinary, and not a failure.
        rusqlite::Error::QueryReturnedNoRows => Ok(None),
        other => Err(other),
    })
}

/// One conversion, so every call site is a `map_err` of the same shape.
fn store(path: &Path, why: &rusqlite::Error) -> Error {
    Error::Store {
        path: path.display().to_string(),
        detail: why.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::{Moved, apply};
    use crate::testenv::Scratch;
    use rusqlite::Connection;
    use std::path::{Path, PathBuf};

    /// A store shaped like the one zeromem makes. Written out rather than taken
    /// from the dependency, so a schema that moves fails here.
    fn store(scratch: &Scratch, turns: i64) -> PathBuf {
        let path = scratch.path().join("memory.db");
        let conn = Connection::open(&path).unwrap();
        conn.execute_batch(
            "CREATE TABLE turns (
                 id INTEGER PRIMARY KEY, session_id TEXT NOT NULL,
                 session_turn INTEGER NOT NULL, speaker TEXT NOT NULL,
                 text TEXT NOT NULL, ts INTEGER NOT NULL);
             CREATE TABLE embeddings (
                 kind TEXT NOT NULL, key TEXT NOT NULL, vec BLOB NOT NULL,
                 PRIMARY KEY (kind, key));",
        )
        .unwrap();

        for id in 1..=turns {
            conn.execute(
                "INSERT INTO turns VALUES (?1, 's', ?1, 'user', ?2, ?1)",
                (id, format!("turn {id}")),
            )
            .unwrap();
            conn.execute(
                "INSERT INTO embeddings VALUES ('turn', ?1, ?2)",
                (id.to_string(), vec![0u8; 8]),
            )
            .unwrap();
        }
        path
    }

    fn count(path: &Path, table: &str) -> i64 {
        Connection::open(path)
            .unwrap()
            .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                row.get(0)
            })
            .unwrap()
    }

    #[test]
    fn a_store_with_no_database_yet_is_nothing_to_do() {
        // First run, and creating a file here would leave zeromem to fix one.
        let scratch = Scratch::new("memory-absent");
        let path = scratch.path().join("memory.db");

        assert_eq!(apply(&path, 100).unwrap(), Moved::default());
        assert!(!path.exists(), "made a database out of nothing");
    }

    #[test]
    fn fewer_turns_than_the_horizon_moves_nothing() {
        // The ordinary state for a long time, and not a failure.
        let scratch = Scratch::new("memory-under");
        let path = store(&scratch, 5);

        assert_eq!(apply(&path, 100).unwrap(), Moved::default());
        assert_eq!(count(&path, "turns"), 5);
    }

    #[test]
    fn the_newest_turns_stay_and_the_rest_are_archived() {
        let scratch = Scratch::new("memory-over");
        let path = store(&scratch, 10);

        assert_eq!(
            apply(&path, 4).unwrap(),
            Moved {
                turns: 6,
                embeddings: 6
            }
        );
        assert_eq!(count(&path, "turns"), 4);
        assert_eq!(count(&path, "archived_turns"), 6);

        // The newest four. Off by one is a store remembering the wrong end.
        let kept: i64 = Connection::open(&path)
            .unwrap()
            .query_row("SELECT MIN(id) FROM turns", [], |row| row.get(0))
            .unwrap();
        assert_eq!(kept, 7);
    }

    #[test]
    fn an_embedding_goes_with_its_turn() {
        // The half the record did not say: turns alone leaves the vectors.
        let scratch = Scratch::new("memory-vectors");
        let path = store(&scratch, 10);

        apply(&path, 4).unwrap();
        assert_eq!(count(&path, "embeddings"), 4);
        assert_eq!(count(&path, "archived_embeddings"), 6);
    }

    #[test]
    fn a_horizon_of_zero_empties_the_live_tables_without_losing_anything() {
        // MAX rather than MAX + 1 would keep the newest turn.
        let scratch = Scratch::new("memory-zero");
        let path = store(&scratch, 3);

        assert_eq!(
            apply(&path, 0).unwrap(),
            Moved {
                turns: 3,
                embeddings: 3
            }
        );
        assert_eq!(count(&path, "turns"), 0);
        assert_eq!(count(&path, "archived_turns"), 3);
    }

    #[test]
    fn applying_it_twice_moves_nothing_the_second_time() {
        // Every launch after the first, and it must be a no-op.
        let scratch = Scratch::new("memory-again");
        let path = store(&scratch, 10);

        apply(&path, 4).unwrap();
        assert_eq!(apply(&path, 4).unwrap(), Moved::default());
        assert_eq!(count(&path, "archived_turns"), 6);
    }

    #[test]
    fn what_was_archived_is_still_there_to_read() {
        // The history stays on disk, or this is a delete with a longer name.
        let scratch = Scratch::new("memory-kept");
        let path = store(&scratch, 10);
        apply(&path, 4).unwrap();

        let text: String = Connection::open(&path)
            .unwrap()
            .query_row("SELECT text FROM archived_turns WHERE id = 1", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(text, "turn 1");
    }

    /// A store zeromem itself made, with `turns` ingested through its own path.
    ///
    /// The hand-written schema above is a claim about what zeromem writes; this
    /// is the same claim checked against the crate. Both are here because the
    /// first is fast and the second is true.
    fn ingested(scratch: &Scratch, turns: i64) -> PathBuf {
        let path = scratch.path().join("real.db");
        let mut memory = zeromem::ZeroMem::open(
            &path,
            zeromem::config::Config::default(),
            Box::new(zeromem::embed::HashEmbedder::default()),
        )
        .expect("zeromem opens a fresh store");

        for turn in 1..=turns {
            memory
                .ingest_turn("s", "user", &format!("turn {turn}"), turn)
                .expect("zeromem takes a turn");
        }
        drop(memory);
        path
    }

    #[test]
    fn the_schema_written_out_above_is_the_one_zeromem_makes() {
        // #284 built the horizon against a hand-written schema, which was the
        // only way to test it before the dependency existed. This is what turns
        // that from an assumption into a check.
        let scratch = Scratch::new("memory-real-schema");
        let path = ingested(&scratch, 10);

        assert_eq!(
            apply(&path, 4).unwrap(),
            Moved {
                turns: 6,
                embeddings: 6
            }
        );
        assert_eq!(count(&path, "turns"), 4);
        assert_eq!(count(&path, "archived_turns"), 6);
    }

    #[test]
    fn zeromem_still_opens_a_store_the_horizon_has_been_through() {
        // The whole claim of the approach, and it needs both halves in one case:
        // ingest, bound, reopen. A horizon that left the file in a state zeromem
        // refused would be worse than no horizon at all.
        let scratch = Scratch::new("memory-real-reopen");
        let path = ingested(&scratch, 8);
        apply(&path, 3).unwrap();

        let memory = zeromem::ZeroMem::open(
            &path,
            zeromem::config::Config::default(),
            Box::new(zeromem::embed::HashEmbedder::default()),
        );
        assert!(memory.is_ok(), "zeromem refused a bounded store");
        assert_eq!(count(&path, "turns"), 3);
    }
}
