#!/usr/bin/env python3
"""Explain-query-plan check for the Tanuki Room queries.

Room is a code generator, not an optimizer: it emits your @Query SQL verbatim and only creates the
indexes you declare. The SQLite query planner then does the real work, using *only* the indexes that
exist. This script makes that concrete — it loads the schema Room actually exports (tables + every
index, from schemas/<db>/<version>.json) into an in-memory SQLite database and runs
`EXPLAIN QUERY PLAN` on each DAO query, so you can see which index each join/filter uses and where a
full scan or a transient sort happens.

It is a diagnostic, not a build gate: it exits non-zero only if a query fails to plan (e.g. drifted
SQL that no longer matches the schema). Run it after changing a query or an index:

    python3 core/data/tools/query_plan_check.py

The DDL is read from the exported schema, so it never drifts from Room. The query text below is the
one hand-maintained mirror of the DAOs — keep it in sync when you change a @Query.
"""

from __future__ import annotations

import glob
import json
import os
import sqlite3
import sys

REPO_SUBPATH = "core/data/schemas/app.tanuki.core.data.database.TanukiDatabase"

# --- The queries to inspect. Mirror of the DAO @Query strings; named params are replaced with a
#     literal ('x') since parameter *values* never change the query plan. Keep in sync with:
#       core/data/src/commonMain/kotlin/app/tanuki/core/data/repository/manga/MangaDao.kt
#       core/data/src/commonMain/kotlin/app/tanuki/core/data/repository/chapter/ChapterDao.kt
UNREAD_SUBQUERY = (
    "(SELECT COUNT(*) FROM chapters "
    "LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id "
    "WHERE chapters.manga_id = manga.id AND COALESCE(reading_progress.already_read, 0) = 0) "
    "AS unread_count"
)

QUERIES: list[tuple[str, str]] = [
    (
        "MangaDao.observeManga",
        f"""
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
               {UNREAD_SUBQUERY}
        FROM manga
        LEFT JOIN history ON history.manga_id = manga.id
        LEFT JOIN library ON library.id = manga.id
        WHERE manga.id = 'x'
        GROUP BY manga.id
        """,
    ),
    (
        "MangaDao.observeLibrary",
        f"""
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
               {UNREAD_SUBQUERY}
        FROM library
        JOIN manga ON manga.id = library.id
        LEFT JOIN history ON history.manga_id = manga.id
        GROUP BY manga.id
        ORDER BY manga.title
        """,
    ),
    (
        "MangaDao.observeMangaBySource",
        f"""
        SELECT manga.*, MAX(history.read_at) AS last_read, library.date_added, library.reading_status,
               {UNREAD_SUBQUERY}
        FROM manga
        LEFT JOIN history ON history.manga_id = manga.id
        LEFT JOIN library ON library.id = manga.id
        WHERE manga.source = 'x'
        GROUP BY manga.id
        ORDER BY manga.title
        """,
    ),
    (
        "ChapterDao.observeChapters",
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.manga_id = 'x'
        ORDER BY chapters.chapter_number
        """,
    ),
    (
        "ChapterDao.observeBookmarkedChapters",
        """
        SELECT chapters.*,
               reading_progress.has_bookmark, reading_progress.last_page_read, reading_progress.already_read
        FROM chapters
        LEFT JOIN reading_progress ON reading_progress.chapter_id = chapters.id
        WHERE chapters.manga_id = 'x' AND reading_progress.has_bookmark = 1
        ORDER BY chapters.chapter_number
        """,
    ),
]


def repo_root() -> str:
    # tools/ -> data/ -> core/ -> <repo root>
    here = os.path.dirname(os.path.abspath(__file__))
    return os.path.abspath(os.path.join(here, "..", "..", ".."))


def latest_schema_path(root: str) -> str:
    schema_dir = os.path.join(root, REPO_SUBPATH)
    versions = glob.glob(os.path.join(schema_dir, "*.json"))
    if not versions:
        sys.exit(
            f"No exported schema found under {schema_dir}. "
            "Build the module once (e.g. ./gradlew :core:data:compileAndroidMain) to export it."
        )
    # File names are the DB version number; pick the highest.
    return max(versions, key=lambda p: int(os.path.splitext(os.path.basename(p))[0]))


def build_db(schema_json: str) -> sqlite3.Connection:
    """Recreate the Room schema (tables + indices) in an in-memory database."""
    with open(schema_json) as f:
        schema = json.load(f)
    conn = sqlite3.connect(":memory:")
    for entity in schema["database"]["entities"]:
        table = entity["tableName"]
        conn.execute(entity["createSql"].replace("${TABLE_NAME}", table))
        for index in entity.get("indices", []):
            conn.execute(index["createSql"].replace("${TABLE_NAME}", table))
    conn.commit()
    return conn


def classify(detail: str) -> str:
    d = detail.upper()
    if "USING INTEGER PRIMARY KEY" in d or "USING PRIMARY KEY" in d or "USING INDEX" in d or "USING COVERING INDEX" in d:
        return "ok"  # an index (or the PK) drives this step
    if d.startswith("SCAN") or " SCAN " in f" {d} ":
        return "scan"  # full table scan — fine on tiny tables, watch on growing ones
    if "TEMP B-TREE" in d:
        return "sort"  # transient b-tree for ORDER BY / GROUP BY (not index-backed)
    return "info"


MARK = {"ok": "  ✓", "scan": "  ⚠ SCAN", "sort": "  ⚠ SORT", "info": "   ·"}


def main() -> int:
    conn = build_db(latest_schema_path(repo_root()))
    scans = 0
    sorts = 0
    failures = 0

    for name, sql in QUERIES:
        print(f"\n== {name} ==")
        try:
            rows = conn.execute("EXPLAIN QUERY PLAN " + sql).fetchall()
        except sqlite3.Error as e:
            failures += 1
            print(f"  ✗ FAILED TO PLAN: {e}")
            print("    (query SQL likely drifted from the schema — update this script or the index)")
            continue
        for _id, _parent, _notused, detail in rows:
            kind = classify(detail)
            scans += kind == "scan"
            sorts += kind == "sort"
            print(f"{MARK[kind]}  {detail}")

    print("\n" + "-" * 60)
    print(f"index-backed steps look healthy; full scans: {scans}, transient sorts: {sorts}")
    print(
        "Notes: a SCAN of a tiny/naturally-small table (e.g. library membership) is expected.\n"
        "       transient SORTs come from ORDER BY manga.title / chapters.chapter_number, which\n"
        "       have no index by design — cheap at current data sizes. Add an index only if a\n"
        "       large library's sort ever measures slow."
    )
    # Only a planning failure (drifted SQL) is treated as an error; scans/sorts are informational.
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())