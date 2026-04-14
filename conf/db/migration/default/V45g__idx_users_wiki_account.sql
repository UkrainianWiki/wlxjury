-- Targets: all simulations (login lookup by wiki_account)
CREATE INDEX idx_users_wiki_account ON users(wiki_account);
