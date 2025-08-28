-- Unify transfer-related notes and display notes in money_log
-- - note:  RemittanceTo${player}   -> Transfer to ${player}
-- - note:  RemmitanceFrom${player} -> Transfer from ${player}
-- - display_note: ${player}へ送金     -> 送金: ${player}
-- - display_note: ${player}からの送金 -> 受取: ${player}

-- note: RemittanceTo{player} -> "Transfer to {player}"
UPDATE money_log
SET note = CONCAT('Transfer to ', SUBSTRING(note, CHAR_LENGTH('RemittanceTo') + 1))
WHERE note LIKE 'RemittanceTo%';

-- note: RemmitanceFrom{player} -> "Transfer from {player}"
UPDATE money_log
SET note = CONCAT('Transfer from ', SUBSTRING(note, CHAR_LENGTH('RemmitanceFrom') + 1))
WHERE note LIKE 'RemmitanceFrom%';

-- display_note: {player}へ送金 -> "送金: {player}"
UPDATE money_log
SET display_note = CONCAT('送金: ', SUBSTRING(display_note, 1, CHAR_LENGTH(display_note) - CHAR_LENGTH('へ送金')))
WHERE display_note LIKE '%へ送金';

-- display_note: {player}からの送金 -> "受取: {player}"
UPDATE money_log
SET display_note = CONCAT('受取: ', SUBSTRING(display_note, 1, CHAR_LENGTH(display_note) - CHAR_LENGTH('からの送金')))
WHERE display_note LIKE '%からの送金';

