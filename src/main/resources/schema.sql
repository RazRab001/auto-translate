-- =====================================================================
-- Demo schéma průmyslového katalogu (analogie IBM Maximo ITEM tabulky).
-- Spouští se automaticky při startu aplikace přes spring.sql.init.mode.
-- Pro produkční nasazení (PostgreSQL/Db2/Oracle) tento soubor vypnout
-- (spring.sql.init.mode=never) a~mířit na reálnou DB přes
-- spring.datasource.url.
-- =====================================================================

DROP TABLE IF EXISTS ITEM;

CREATE TABLE ITEM (
    ITEMNUM         VARCHAR(32)  PRIMARY KEY,
    DESCRIPTION     VARCHAR(200) NOT NULL,
    LONGDESCRIPTION VARCHAR(2000),
    CATEGORY        VARCHAR(50),
    UNIT            VARCHAR(20)
);

CREATE INDEX IDX_ITEM_CATEGORY ON ITEM(CATEGORY);
