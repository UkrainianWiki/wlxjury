-- A user added by wiki account only ("Import users" with a `User:Name` line, e.g. a
-- board member whose files are conflicts of interest) has no email. users.email was
-- NOT NULL and UNIQUE, so such users were stored with '' and only one could exist: a
-- second failed on the unique key. No email is NULL now, which the unique key allows
-- any number of. Charset and collation as the table's (B44).
ALTER TABLE users MODIFY email varchar(190) NULL;

UPDATE users SET email = NULL WHERE email = '';
