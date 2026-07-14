import json
import random

random.seed(42)

# Maker Sequence Training Data Generator
# Produces completion patterns for each task category:
# - What agent sequences lead to SUCCESS
# - What agent sequences lead to FAILURE/INCOMPLETE
# - What tool patterns indicate completion
# - Typical turn counts per category

# ═══════════════════════════════════════════════════════════════
# COMPLETION PATTERNS: category → {agents, tools, turns, success}
# ═══════════════════════════════════════════════════════════════

PATTERNS = {
    "CODING": {
        "success": [
            # Standard: design → code → test → commit
            {"agents": ["Architect", "Coder", "Tester", "GitAgent"], "tools": ["file_read", "file_write", "shell", "shell"], "turns_range": (3, 6)},
            {"agents": ["Coder", "Tester", "GitAgent"], "tools": ["file_read", "file_write", "shell", "shell"], "turns_range": (2, 4)},
            {"agents": ["Coder", "Tester"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 4)},
            {"agents": ["Coder", "CodeEditor", "Tester"], "tools": ["file_read", "file_write", "safe_write", "shell"], "turns_range": (3, 5)},
            {"agents": ["Architect", "Coder", "CodeEditor", "Tester", "GitAgent"], "tools": ["file_read", "file_write", "safe_write", "shell", "shell"], "turns_range": (4, 7)},
            # Quick fix
            {"agents": ["Coder", "CodeEditor"], "tools": ["file_read", "file_write", "safe_write"], "turns_range": (1, 3)},
            {"agents": ["Coder"], "tools": ["file_read", "file_write"], "turns_range": (1, 2)},
            # With codebase search
            {"agents": ["Coder", "CodeEditor", "Tester"], "tools": ["codebase_search", "file_read", "file_write", "shell"], "turns_range": (3, 5)},
        ],
        "failure": [
            {"agents": ["Coder"], "tools": ["file_read"], "turns_range": (1, 2)},  # Only read, never wrote
            {"agents": ["Coder", "Coder", "Coder"], "tools": ["file_read", "file_read", "file_read"], "turns_range": (3, 5)},  # Stalled
            {"agents": ["Coder", "Tester"], "tools": ["file_write", "shell"], "turns_range": (2, 3), "test_failed": True},  # Tests failed
        ]
    },
    "TESTING": {
        "success": [
            {"agents": ["Tester"], "tools": ["file_read", "file_write", "shell"], "turns_range": (1, 3)},
            {"agents": ["Tester", "Tester"], "tools": ["file_read", "file_write", "shell", "shell"], "turns_range": (2, 3)},
            {"agents": ["Coder", "Tester"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 4)},
            {"agents": ["Tester", "SysAdmin"], "tools": ["file_write", "shell", "shell"], "turns_range": (2, 3)},
            # E2E testing
            {"agents": ["Tester"], "tools": ["file_write", "selenium", "shell"], "turns_range": (2, 4)},
        ],
        "failure": [
            {"agents": ["Tester"], "tools": ["file_read"], "turns_range": (1, 1)},  # Only read test files
            {"agents": ["Coder"], "tools": ["file_read", "file_write"], "turns_range": (1, 2)},  # Coder wrote but never ran
        ]
    },
    "GIT": {
        "success": [
            {"agents": ["GitAgent"], "tools": ["shell"], "turns_range": (1, 2)},
            {"agents": ["GitAgent"], "tools": ["shell", "shell"], "turns_range": (1, 2)},
            {"agents": ["SysAdmin", "GitAgent"], "tools": ["shell", "shell"], "turns_range": (2, 3)},
        ],
        "failure": [
            {"agents": ["SysAdmin"], "tools": ["shell"], "turns_range": (1, 1)},  # SysAdmin ran git but shouldn't
        ]
    },
    "DEVOPS": {
        "success": [
            {"agents": ["DevOps"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 4)},
            {"agents": ["DevOps", "SysAdmin"], "tools": ["file_write", "shell", "shell"], "turns_range": (2, 4)},
            {"agents": ["Architect", "DevOps"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 5)},
            {"agents": ["DevOps", "DevOps"], "tools": ["file_write", "file_write", "shell"], "turns_range": (2, 4)},
            # Docker build + deploy
            {"agents": ["DevOps", "SysAdmin"], "tools": ["file_write", "shell", "shell", "shell"], "turns_range": (3, 5)},
        ],
        "failure": [
            {"agents": ["DevOps"], "tools": ["file_read"], "turns_range": (1, 1)},
            {"agents": ["Coder"], "tools": ["file_write"], "turns_range": (1, 2)},  # Wrong agent
        ]
    },
    "SECURITY": {
        "success": [
            {"agents": ["SecurityAuditor"], "tools": ["file_read", "codebase_search", "shell"], "turns_range": (2, 4)},
            {"agents": ["SecurityAuditor", "Coder"], "tools": ["file_read", "codebase_search", "file_write"], "turns_range": (2, 4)},
            {"agents": ["SecurityAuditor", "CodeEditor", "Tester"], "tools": ["codebase_search", "file_write", "shell"], "turns_range": (3, 5)},
        ],
        "failure": [
            {"agents": ["SecurityAuditor"], "tools": ["file_read"], "turns_range": (1, 1)},  # Only scanned, no report
        ]
    },
    "ARCHITECTURE": {
        "success": [
            {"agents": ["Architect"], "tools": ["file_read", "codebase_search"], "turns_range": (1, 3)},
            {"agents": ["Architect"], "tools": ["file_read", "graph_memory"], "turns_range": (1, 3)},
            {"agents": ["Architect", "Coder"], "tools": ["file_read", "codebase_search", "file_read"], "turns_range": (2, 4)},
            {"agents": ["Architect"], "tools": ["file_read", "codebase_search", "central_memory"], "turns_range": (2, 3)},
        ],
        "failure": [
            {"agents": ["Coder"], "tools": ["file_read"], "turns_range": (1, 1)},  # Wrong agent for architecture
        ]
    },
    "DATABASE": {
        "success": [
            {"agents": ["DatabaseAdmin"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 4)},
            {"agents": ["DatabaseAdmin", "Tester"], "tools": ["file_write", "shell", "shell"], "turns_range": (2, 4)},
            {"agents": ["Architect", "DatabaseAdmin"], "tools": ["file_read", "file_write"], "turns_range": (2, 3)},
            {"agents": ["DatabaseAdmin"], "tools": ["file_write", "shell"], "turns_range": (1, 3)},
        ],
        "failure": [
            {"agents": ["DatabaseAdmin"], "tools": ["file_read"], "turns_range": (1, 1)},
        ]
    },
    "DOCS": {
        "success": [
            {"agents": ["DocWriter"], "tools": ["file_read", "file_write"], "turns_range": (1, 3)},
            {"agents": ["DocWriter"], "tools": ["file_read", "file_write", "fetch_url"], "turns_range": (2, 3)},
            {"agents": ["Coder", "DocWriter"], "tools": ["file_read", "file_write"], "turns_range": (2, 3)},
        ],
        "failure": [
            {"agents": ["DocWriter"], "tools": ["file_read"], "turns_range": (1, 1)},
        ]
    },
    "SYSADMIN": {
        "success": [
            {"agents": ["SysAdmin"], "tools": ["shell"], "turns_range": (1, 2)},
            {"agents": ["SysAdmin"], "tools": ["shell", "shell"], "turns_range": (1, 2)},
            {"agents": ["SysAdmin", "SysAdmin"], "tools": ["shell", "file_read", "shell"], "turns_range": (2, 3)},
        ],
        "failure": [
            {"agents": ["SysAdmin"], "tools": [], "turns_range": (1, 1)},  # No tools invoked
        ]
    },
    "DATA": {
        "success": [
            {"agents": ["DataAnalyst"], "tools": ["file_read", "shell", "file_write"], "turns_range": (2, 4)},
            {"agents": ["DataAnalyst"], "tools": ["file_read", "shell"], "turns_range": (1, 3)},
            {"agents": ["DataAnalyst", "DocWriter"], "tools": ["file_read", "shell", "file_write"], "turns_range": (2, 4)},
        ],
        "failure": [
            {"agents": ["DataAnalyst"], "tools": ["file_read"], "turns_range": (1, 1)},
        ]
    },
    "GOALS": {
        "success": [
            {"agents": ["GoalTracker"], "tools": ["file_read", "central_memory"], "turns_range": (1, 2)},
            {"agents": ["Architect", "GoalTracker"], "tools": ["file_read", "central_memory"], "turns_range": (2, 3)},
            {"agents": ["GoalTracker"], "tools": ["central_memory"], "turns_range": (1, 1)},
        ],
        "failure": [
            {"agents": ["Coder"], "tools": ["file_read"], "turns_range": (1, 1)},
        ]
    },
    "ANDROID": {
        "success": [
            {"agents": ["AndroidDev"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 5)},
            {"agents": ["AndroidDev", "Tester"], "tools": ["file_read", "file_write", "shell", "shell"], "turns_range": (3, 5)},
            {"agents": ["Architect", "AndroidDev"], "tools": ["file_read", "file_write"], "turns_range": (2, 4)},
        ],
        "failure": [
            {"agents": ["Coder"], "tools": ["file_read", "file_write"], "turns_range": (1, 2)},  # Generic coder for Android
        ]
    },
    "IOS": {
        "success": [
            {"agents": ["IosDev"], "tools": ["file_read", "file_write", "shell"], "turns_range": (2, 5)},
            {"agents": ["IosDev", "Tester"], "tools": ["file_read", "file_write", "shell", "shell"], "turns_range": (3, 5)},
            {"agents": ["Architect", "IosDev"], "tools": ["file_read", "file_write"], "turns_range": (2, 4)},
        ],
        "failure": [
            {"agents": ["Coder"], "tools": ["file_read", "file_write"], "turns_range": (1, 2)},
        ]
    },
}

# ═══════════════════════════════════════════════════════════════
# GENERATE 10000 sequences
# ═══════════════════════════════════════════════════════════════

lines = []

for category, patterns in PATTERNS.items():
    # Generate ~700 success sequences per category (varied)
    success_patterns = patterns["success"]
    for _ in range(700):
        pattern = random.choice(success_patterns)
        turns = random.randint(*pattern["turns_range"])
        # Add some random variation to tools
        tools = list(pattern["tools"])
        if random.random() > 0.7:
            tools.append(random.choice(["file_read", "clipboard", "codebase_search"]))
        
        entry = {
            "category": category,
            "agents": pattern["agents"],
            "tools": tools,
            "turns": turns,
            "success": True
        }
        lines.append(entry)

    # Generate ~100 failure sequences per category
    failure_patterns = patterns["failure"]
    for _ in range(100):
        pattern = random.choice(failure_patterns)
        turns = random.randint(*pattern["turns_range"])
        
        entry = {
            "category": category,
            "agents": pattern["agents"],
            "tools": list(pattern["tools"]),
            "turns": turns,
            "success": False
        }
        lines.append(entry)

# Shuffle
random.shuffle(lines)

# Write as JSONL
output_path = "C:/DevTools/rblab/mkpro/datajsonl/maker_sequences.jsonl"
with open(output_path, "w", encoding="utf-8") as f:
    for line in lines:
        f.write(json.dumps(line, ensure_ascii=False) + "\n")

print(f"Generated {len(lines)} Maker sequence training examples")
print(f"  Success: {sum(1 for l in lines if l['success'])}")
print(f"  Failure: {sum(1 for l in lines if not l['success'])}")
print(f"  Categories: {len(PATTERNS)}")
print(f"  Output: {output_path}")
