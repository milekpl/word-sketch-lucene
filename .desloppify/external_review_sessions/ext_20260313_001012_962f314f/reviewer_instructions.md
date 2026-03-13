# External Blind Review Session

Session id: ext_20260313_001012_962f314f
Session token: cfc4db6af115f01c17f44403b7964d71
Blind packet: /mnt/d/git/word-sketch-lucene/.desloppify/review_packet_blind.json
Template output: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260313_001012_962f314f/review_result.template.json
Claude launch prompt: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260313_001012_962f314f/claude_launch_prompt.md
Expected reviewer output: /mnt/d/git/word-sketch-lucene/.desloppify/external_review_sessions/ext_20260313_001012_962f314f/review_result.json

Happy path:
1. Open the Claude launch prompt file and paste it into a context-isolated subagent task.
2. Reviewer writes JSON output to the expected reviewer output path.
3. Submit with the printed --external-submit command.

Reviewer output requirements:
1. Return JSON with top-level keys: session, assessments, issues.
2. session.id must be `ext_20260313_001012_962f314f`.
3. session.token must be `cfc4db6af115f01c17f44403b7964d71`.
4. Include issues with required schema fields (dimension/identifier/summary/related_files/evidence/suggestion/confidence).
5. Use the blind packet only (no score targets or prior context).
