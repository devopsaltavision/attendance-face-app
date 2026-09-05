#!/usr/bin/env python3
"""Read-only SQLite schema and image-reference inspector for the local BetterHR copy."""
import argparse
import json
import sqlite3
from pathlib import Path


KEYWORDS = ("face", "photo", "image", "filename", "file_path", "person", "user", "employee", "staff", "member", "identity", "template")


def quoted(name):
    return '"' + name.replace('"', '""') + '"'


def inspect_database(path):
    uri = f"file:{path.resolve().as_posix()}?mode=ro"
    connection = sqlite3.connect(uri, uri=True)
    try:
        result = {"file": path.name, "readable": True, "tables": []}
        tables = connection.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").fetchall()
        for (table,) in tables:
            columns = connection.execute(f"PRAGMA table_info({quoted(table)})").fetchall()
            indexes = connection.execute(f"PRAGMA index_list({quoted(table)})").fetchall()
            index_details = []
            for index in indexes:
                index_name = index[1]
                index_details.append({"name": index_name, "unique": bool(index[2]), "columns": [entry[2] for entry in connection.execute(f"PRAGMA index_info({quoted(index_name)})").fetchall()]})
            row_count = connection.execute(f"SELECT COUNT(*) FROM {quoted(table)}").fetchone()[0]
            references = {}
            for column in columns:
                name = column[1]
                try:
                    count = connection.execute(f"SELECT COUNT(*) FROM {quoted(table)} WHERE CAST({quoted(name)} AS TEXT) LIKE '%FILENAME-%'").fetchone()[0]
                except sqlite3.DatabaseError:
                    count = 0
                if count:
                    references[name] = count
            result["tables"].append({
                "name": table,
                "row_count": row_count,
                "columns": [{"name": column[1], "type": column[2], "primary_key": bool(column[5])} for column in columns],
                "indexes": index_details,
                "keyword_columns": [column[1] for column in columns if any(keyword in column[1].lower() for keyword in KEYWORDS)],
                "filename_reference_columns": references,
            })
        return result
    finally:
        connection.close()


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument("--database-dir", type=Path, default=root / ".tmp" / "face-database" / "Download")
    parser.add_argument("--output", type=Path, default=root / ".tmp" / "betterhr-db-schema-audit.json")
    args = parser.parse_args()
    databases = sorted(path for path in args.database_dir.iterdir() if path.is_file() and path.suffix == ".db")
    report = {"mode": "SQLite read-only", "databases": []}
    for database in databases:
        try:
            report["databases"].append(inspect_database(database))
        except sqlite3.DatabaseError as error:
            report["databases"].append({"file": database.name, "readable": False, "error": str(error)})
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
