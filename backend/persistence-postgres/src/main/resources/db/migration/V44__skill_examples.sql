-- V44: skills.examples -- SkillHub examples field (#352, I9, Tencent raw 20/28).
-- Frontmatter `examples` (string array, <=10 entries x <=512 chars, validated
-- at upload) carried through to the market/detail surfaces; the API-shape
-- examples list on AgentCard-style consumers.
ALTER TABLE skills
    ADD COLUMN examples text[] NOT NULL DEFAULT '{}';
