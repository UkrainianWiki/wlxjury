/*M!999999\- enable the sandbox mode */ 
-- MariaDB dump 10.19  Distrib 10.6.22-MariaDB, for debian-linux-gnu (x86_64)
--
-- Host: localhost    Database: fw_fresh
-- ------------------------------------------------------
-- Server version	10.6.22-MariaDB-ubu2004

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Table structure for table `account_token`
--

DROP TABLE IF EXISTS `account_token`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `account_token` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) unsigned NOT NULL,
  `purpose` varchar(16) NOT NULL,
  `token_hash` char(64) NOT NULL,
  `email` varchar(190) NOT NULL,
  `contest_id` bigint(20) unsigned DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  `created_by` bigint(20) unsigned DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3),
  `expires_at` timestamp(3) NOT NULL DEFAULT '0000-00-00 00:00:00.000',
  `used_at` timestamp(3) NULL DEFAULT NULL,
  `revoked_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `account_token_hash_uidx` (`token_hash`),
  KEY `account_token_user_idx` (`user_id`,`purpose`),
  KEY `account_token_contest_idx` (`contest_id`),
  CONSTRAINT `fk_account_token_contest` FOREIGN KEY (`contest_id`) REFERENCES `contest_jury` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_account_token_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_account_token_purpose` CHECK (cast(`purpose` as char charset binary) in ('invitation','password_reset','email_change')),
  CONSTRAINT `chk_account_token_role` CHECK (`role` is null or cast(`role` as char charset binary) in ('no','jury','organizer','admin','board','auditor','staff'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `account_token`
--

LOCK TABLES `account_token` WRITE;
/*!40000 ALTER TABLE `account_token` DISABLE KEYS */;
/*!40000 ALTER TABLE `account_token` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `api_token`
--

DROP TABLE IF EXISTS `api_token`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `api_token` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` bigint(20) unsigned NOT NULL,
  `name` varchar(100) NOT NULL,
  `token_hash` char(64) NOT NULL,
  `prefix` varchar(16) NOT NULL,
  `create_nonce` char(64) DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3),
  `expires_at` timestamp(3) NULL DEFAULT NULL,
  `last_used_at` timestamp(3) NULL DEFAULT NULL,
  `revoked_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `api_token_hash_uidx` (`token_hash`),
  KEY `api_token_user_idx` (`user_id`),
  CONSTRAINT `fk_api_token_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `api_token`
--

LOCK TABLES `api_token` WRITE;
/*!40000 ALTER TABLE `api_token` DISABLE KEYS */;
/*!40000 ALTER TABLE `api_token` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `category`
--

DROP TABLE IF EXISTS `category`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `category` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(190) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `title` (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `category`
--

LOCK TABLES `category` WRITE;
/*!40000 ALTER TABLE `category` DISABLE KEYS */;
/*!40000 ALTER TABLE `category` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `category_members`
--

DROP TABLE IF EXISTS `category_members`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `category_members` (
  `category_id` bigint(20) unsigned NOT NULL,
  `page_id` bigint(20) NOT NULL,
  UNIQUE KEY `cm_category_page` (`category_id`,`page_id`),
  KEY `FK_category_member_page_id` (`page_id`),
  CONSTRAINT `FK_category_member_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_category_member_page_id` FOREIGN KEY (`page_id`) REFERENCES `images` (`page_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `category_members`
--

LOCK TABLES `category_members` WRITE;
/*!40000 ALTER TABLE `category_members` DISABLE KEYS */;
/*!40000 ALTER TABLE `category_members` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `category_members_orphans`
--

DROP TABLE IF EXISTS `category_members_orphans`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `category_members_orphans` (
  `category_id` bigint(20) unsigned NOT NULL,
  `page_id` bigint(20) NOT NULL,
  UNIQUE KEY `cm_category_page` (`category_id`,`page_id`),
  KEY `FK_category_member_page_id` (`page_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `category_members_orphans`
--

LOCK TABLES `category_members_orphans` WRITE;
/*!40000 ALTER TABLE `category_members_orphans` DISABLE KEYS */;
/*!40000 ALTER TABLE `category_members_orphans` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `comment`
--

DROP TABLE IF EXISTS `comment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `username` varchar(255) NOT NULL,
  `round_id` int(11) DEFAULT NULL,
  `created_at` varchar(40) DEFAULT NULL,
  `body` text NOT NULL,
  `room` int(11) NOT NULL DEFAULT 1,
  `contest_id` bigint(20) unsigned DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_comment_contest` (`contest_id`),
  CONSTRAINT `FK_comment_contest` FOREIGN KEY (`contest_id`) REFERENCES `contest_jury` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `comment`
--

LOCK TABLES `comment` WRITE;
/*!40000 ALTER TABLE `comment` DISABLE KEYS */;
/*!40000 ALTER TABLE `comment` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `contest_jury`
--

DROP TABLE IF EXISTS `contest_jury`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `contest_jury` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(255) NOT NULL DEFAULT 'Wiki Loves Earth',
  `country` varchar(255) NOT NULL,
  `year` int(11) NOT NULL,
  `images` varchar(4000) DEFAULT NULL,
  `current_round` int(11) DEFAULT 0,
  `monument_id_template` varchar(128) DEFAULT NULL,
  `greeting` text DEFAULT NULL,
  `use_greeting` tinyint(1) DEFAULT NULL,
  `category_id` bigint(20) unsigned DEFAULT NULL,
  `campaign` varchar(32) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_contest_category` (`category_id`),
  CONSTRAINT `FK_contest_category` FOREIGN KEY (`category_id`) REFERENCES `category` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `contest_jury`
--

LOCK TABLES `contest_jury` WRITE;
/*!40000 ALTER TABLE `contest_jury` DISABLE KEYS */;
/*!40000 ALTER TABLE `contest_jury` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `criteria`
--

DROP TABLE IF EXISTS `criteria`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `criteria` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `round` int(11) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `contest` int(11) DEFAULT NULL,
  UNIQUE KEY `id` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `criteria`
--

LOCK TABLES `criteria` WRITE;
/*!40000 ALTER TABLE `criteria` DISABLE KEYS */;
/*!40000 ALTER TABLE `criteria` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `criteria_rate`
--

DROP TABLE IF EXISTS `criteria_rate`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `criteria_rate` (
  `id` bigint(20) unsigned NOT NULL,
  `vote_id` int(11) NOT NULL,
  `criteria` int(11) NOT NULL,
  `rate` int(11) NOT NULL,
  UNIQUE KEY `id` (`id`),
  KEY `criteria_rate_vote_id_idx` (`vote_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `criteria_rate`
--

LOCK TABLES `criteria_rate` WRITE;
/*!40000 ALTER TABLE `criteria_rate` DISABLE KEYS */;
/*!40000 ALTER TABLE `criteria_rate` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `criteria_rate_orphan`
--

DROP TABLE IF EXISTS `criteria_rate_orphan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `criteria_rate_orphan` (
  `archive_id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `id` bigint(20) unsigned NOT NULL,
  `vote_id` int(11) NOT NULL,
  `criteria` int(11) NOT NULL,
  `rate` int(11) NOT NULL,
  `archived_at` timestamp NOT NULL DEFAULT current_timestamp(),
  PRIMARY KEY (`archive_id`),
  KEY `criteria_rate_orphan_vote_id_idx` (`vote_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `criteria_rate_orphan`
--

LOCK TABLES `criteria_rate_orphan` WRITE;
/*!40000 ALTER TABLE `criteria_rate_orphan` DISABLE KEYS */;
/*!40000 ALTER TABLE `criteria_rate_orphan` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int(11) NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int(11) DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT current_timestamp(),
  `execution_time` int(11) NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `flyway_schema_history`
--

LOCK TABLES `flyway_schema_history` WRITE;
/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'1','Create table selection','SQL','V1__Create_table_selection.sql',1128036440,'root','2026-11-03 01:43:20',3,1),(2,'2','Create table users','SQL','V2__Create_table_users.sql',1399049045,'root','2026-11-03 01:43:20',3,1),(3,'3','Create table rounds','SQL','V3__Create_table_rounds.sql',-1724470196,'root','2026-11-03 01:43:20',4,1),(4,'4','Create table contest','SQL','V4__Create_table_contest.sql',329002143,'root','2026-11-03 01:43:20',3,1),(5,'5','Create table images','SQL','V5__Create_table_images.sql',1936644252,'root','2026-11-03 01:43:20',3,1),(6,'6','Create table monument','SQL','V6__Create_table_monument.sql',-1770125199,'root','2026-11-03 01:43:20',3,1),(7,'7','Create indicies','SQL','V7__Create_indicies.sql',1586518387,'root','2026-11-03 01:43:20',9,1),(8,'8','Create table comment','SQL','V8__Create_table_comment.sql',-1268153887,'root','2026-11-03 01:43:20',3,1),(9,'9','Create table criteria','SQL','V9__Create_table_criteria.sql',1617315125,'root','2026-11-03 01:43:20',3,1),(10,'10','Create table criteria rate','SQL','V10__Create_table_criteria_rate.sql',-1569705399,'root','2026-11-03 01:43:20',6,1),(11,'11','Alter images add description','SQL','V11__Alter_images_add_description.sql',-1494503291,'root','2026-11-03 01:43:20',3,1),(12,'12','Alter contest add greeting','SQL','V12__Alter_contest_add_greeting.sql',-1035083259,'root','2026-11-03 01:43:20',4,1),(13,'13','Alter rounds add filters','SQL','V13__Alter_rounds_add_filters.sql',576480419,'root','2026-11-03 01:43:20',5,1),(14,'14','Alter rounds add category filter','SQL','V14__Alter_rounds_add_category_filter.sql',6434584,'root','2026-11-03 01:43:20',3,1),(15,'15','Alter rounds add regions','SQL','V15__Alter_rounds_add_regions.sql',-166749951,'root','2026-11-03 01:43:20',3,1),(16,'16','Alter users add wiki account','SQL','V16__Alter_users_add_wiki_account.sql',1951023437,'root','2026-11-03 01:43:20',3,1),(17,'17','Alter images add size','SQL','V17__Alter_images_add_size.sql',90906416,'root','2026-11-03 01:43:20',3,1),(18,'18','Alter rounds add image size filter','SQL','V18__Alter_rounds_add_image_size_filter.sql',-1454119720,'root','2026-11-03 01:43:20',3,1),(19,'19','Alter rounds add criteria','SQL','V19__Alter_rounds_add_criteria.sql',616670360,'root','2026-11-03 01:43:20',3,1),(20,'20','Alter monuments add regions','SQL','V20__Alter_monuments_add_regions.sql',-329872699,'root','2026-11-03 01:43:20',4,1),(21,'21','Alter rounds add halfstar','SQL','V21__Alter_rounds_add_halfstar.sql',653613738,'root','2026-11-03 01:43:20',3,1),(22,'22','Alter rounds add monuments','SQL','V22__Alter_rounds_add_monuments.sql',656763604,'root','2026-11-03 01:43:20',3,1),(23,'23','Alter users drop default contest','SQL','V23__Alter_users_drop_default_contest.sql',-1305285682,'root','2026-11-03 01:43:20',3,1),(24,'24','Image categories','SQL','V24__Image_categories.sql',-724591449,'root','2026-11-03 01:43:20',16,1),(25,'25','Alter images add author','SQL','V25__Alter_images_add_author.sql',479563863,'root','2026-11-03 01:43:20',2,1),(26,'26','Alter comments add contest','SQL','V26__Alter_comments_add_contest.sql',535715910,'root','2026-11-03 01:43:20',5,1),(27,'27','Up monuments limits','SQL','V27__Up_monuments_limits.sql',287456163,'root','2026-11-03 01:43:20',2,1),(28,'28','Alter selection rename round id','SQL','V28__Alter_selection_rename_round_id.sql',986662750,'root','2026-11-03 01:43:20',3,1),(29,'29','Alter comment rename round id','SQL','V29__Alter_comment_rename_round_id.sql',-689622485,'root','2026-11-03 01:43:20',3,1),(30,'30','Alter user rename contest id','SQL','V30__Alter_user_rename_contest_id.sql',-492390237,'root','2026-11-03 01:43:20',3,1),(31,'31','Alter round rename contest id','SQL','V31__Alter_round_rename_contest_id.sql',-565528515,'root','2026-11-03 01:43:20',2,1),(32,'32','Alter rounds add top images filter','SQL','V32__Alter_rounds_add_top_images_filter.sql',-1236035808,'root','2026-11-03 01:43:20',3,1),(33,'33','Alter images add monuments index','SQL','V33__Alter_images_add_monuments_index.sql',2036324956,'root','2026-11-03 01:43:20',3,1),(34,'34','Alter user add sort','SQL','V34__Alter_user_add_sort.sql',-280575419,'root','2026-11-03 01:43:20',3,1),(35,'35','Alter rounds add special nomination filter','SQL','V35__Alter_rounds_add_special_nomination_filter.sql',1910007867,'root','2026-11-03 01:43:20',2,1),(36,'36','Alter contest add campaign','SQL','V36__Alter_contest_add_campaign.sql',1157350328,'root','2026-11-03 01:43:20',2,1),(37,'37','Round User','SQL','V37__Round_User.sql',-1009487865,'root','2026-11-03 01:43:20',7,1),(38,'38','Migrate round user','SQL','V38__Migrate_round_user.sql',1360418473,'root','2026-11-03 01:43:20',2,1),(39,'39','Alter monuments add subregions','SQL','V39__Alter_monuments_add_subregions.sql',1663589217,'root','2026-11-03 01:43:20',4,1),(40,'40','Alter selection drop timestamps','SQL','V40__Alter_selection_drop_timestamps.sql',568258536,'root','2026-11-03 01:43:21',3,1),(41,'41','Alter round decimal prev rate','SQL','V41__Alter_round_decimal_prev_rate.sql',1490732315,'root','2026-11-03 01:43:21',3,1),(42,'42','Alter rounds add media type filter','SQL','V42__Alter_rounds_add_media_type_filter.sql',1277118315,'root','2026-11-03 01:43:21',3,1),(43,'43','Alter images add mime','SQL','V43__Alter_images_add_mime.sql',584537,'root','2026-11-03 01:43:21',2,1),(44,'44','Alter images title index','SQL','V44__Alter_images_title_index.sql',-1606981986,'root','2026-11-03 01:43:21',6,1),(45,'46','Schema cleanup','SQL','V46__Schema_cleanup.sql',1664957425,'root','2026-11-03 01:43:21',4,1),(46,'47','Analyze tables after index changes','SQL','V47__Analyze_tables_after_index_changes.sql',78369460,'root','2026-11-03 01:43:21',5,1),(47,'48','denormalize selection monument id','SQL','V48__denormalize_selection_monument_id.sql',-2096077671,'root','2026-11-03 01:43:21',13,1),(48,'49','idx selection round jury rate','SQL','V49__idx_selection_round_jury_rate.sql',576573393,'root','2026-11-03 01:43:21',5,1),(49,'50','idx selection round monument id','SQL','V50__idx_selection_round_monument_id.sql',-1503679091,'root','2026-11-03 01:43:21',5,1),(50,'51','monument id unique','SQL','V51__monument_id_unique.sql',359765642,'root','2026-11-03 01:43:21',3,1),(51,'52','Alter rounds previous to csv','SQL','V52__Alter_rounds_previous_to_csv.sql',-1058953621,'root','2026-11-03 01:43:21',4,1),(52,'53','Add unique selection index','SQL','V53__Add_unique_selection_index.sql',1978450078,'root','2026-11-03 01:43:21',8,1),(53,'54','Alter images add media type','SQL','V54__Alter_images_add_media_type.sql',-1983752795,'root','2026-11-03 01:43:21',3,1),(54,'55','Create skipped V45 indexes','SQL','V55__Create_skipped_V45_indexes.sql',2057557874,'root','2026-11-03 01:43:21',13,1),(55,'55.1','Convert to InnoDB','SQL','V55_1__Convert_to_InnoDB.sql',1714914127,'root','2026-11-03 01:43:21',21,1),(56,'56','idx selection round page rate','SQL','V56__idx_selection_round_page_rate.sql',-317397342,'root','2026-11-03 01:43:21',5,1),(57,'57','Resync selection monument id','SQL','V57__Resync_selection_monument_id.sql',-1900800220,'root','2026-11-03 01:43:21',3,1),(58,'58','Selection page id foreign key','SQL','V58__Selection_page_id_foreign_key.sql',1350782631,'root','2026-11-03 01:43:21',8,1),(59,'59','Rounds submit token','SQL','V59__Rounds_submit_token.sql',526009849,'root','2026-11-03 01:43:21',4,1),(60,'60','Background jobs','SQL','V60__Background_jobs.sql',-159955352,'root','2026-11-03 01:43:21',4,1),(61,'61','Add is root and user contest','SQL','V61__Add_is_root_and_user_contest.sql',-32811846,'root','2026-11-03 01:43:21',7,1),(62,'62','Migrate roles to user contest','SQL','V62__Migrate_roles_to_user_contest.sql',-1516337748,'root','2026-11-03 01:43:21',10,1),(63,'63','Deleted and renamed files','SQL','V63__Deleted_and_renamed_files.sql',-221543359,'root','2026-11-03 01:43:21',7,1),(64,'64','Index deleted files','SQL','V64__Index_deleted_files.sql',1588813848,'root','2026-11-03 01:43:21',3,1),(65,'65','Category members foreign keys','SQL','V65__Category_members_foreign_keys.sql',2041987344,'root','2026-11-03 01:43:21',10,1),(66,'66','Index images page monument','SQL','V66__Index_images_page_monument.sql',-695452059,'root','2026-11-03 01:43:21',2,1),(67,'67','Users email nullable','SQL','V67__Users_email_nullable.sql',-1098681713,'root','2026-11-03 01:43:21',5,1),(68,'68','Rounds exclude coi','SQL','V68__Rounds_exclude_coi.sql',2094434308,'root','2026-11-03 01:43:21',3,1),(69,'69','Rounds exclude category','SQL','V69__Rounds_exclude_category.sql',1231623677,'root','2026-11-03 01:43:21',3,1),(70,'70','Named category clauses','SQL','V70__Named_category_clauses.sql',348785794,'root','2026-11-03 01:43:21',6,1),(71,'71','Users add theme','SQL','V71__Users_add_theme.sql',-1799836997,'root','2026-11-03 01:43:21',3,1),(72,'72','Rounds top binary order','SQL','V72__Rounds_top_binary_order.sql',436964276,'root','2026-11-03 01:43:21',4,1),(73,'73','Rounds prev selected by zero to null','SQL','V73__Rounds_prev_selected_by_zero_to_null.sql',1957373462,'root','2026-11-03 01:43:21',3,1),(74,'74','Rounds prev rejected by','SQL','V74__Rounds_prev_rejected_by.sql',-1678597579,'root','2026-11-03 01:43:21',3,1),(75,'75','Api tokens','SQL','V75__Api_tokens.sql',1141901420,'root','2026-11-03 01:43:21',3,1),(76,'76','User contest role allowlist','SQL','V76__User_contest_role_allowlist.sql',2073754549,'root','2026-11-03 01:43:21',7,1),(77,'77','Account verification and tokens','SQL','V77__Account_verification_and_tokens.sql',-1012949477,'root','2026-11-03 01:43:21',8,1),(78,'78','Criteria rate orphans','SQL','V78__Criteria_rate_orphans.sql',-369768825,'root','2026-11-03 01:43:21',5,1),(79,'79','Api token create nonce','SQL','V79__Api_token_create_nonce.sql',-1032183890,'root','2026-11-03 01:43:21',3,1),(80,'80','Session upgrade window','SQL','V80__Session_upgrade_window.sql',-1613836300,'root','2026-11-03 01:43:21',4,1),(81,'81','Legacy password retirement','SQL','V81__Legacy_password_retirement.sql',-510140169,'root','2026-11-03 01:43:21',5,1),(82,'82','Rename selection to vote','SQL','V82__Rename_selection_to_vote.sql',2125039119,'root','2026-11-03 01:43:21',19,1);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `image_renames`
--

DROP TABLE IF EXISTS `image_renames`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `image_renames` (
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  `page_id` bigint(20) NOT NULL,
  `renamed_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3),
  PRIMARY KEY (`title`),
  KEY `image_renames_page_id_idx` (`page_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `image_renames`
--

LOCK TABLES `image_renames` WRITE;
/*!40000 ALTER TABLE `image_renames` DISABLE KEYS */;
/*!40000 ALTER TABLE `image_renames` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `images`
--

DROP TABLE IF EXISTS `images`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `images` (
  `page_id` bigint(20) NOT NULL,
  `contest` bigint(20) NOT NULL DEFAULT 0,
  `title` varchar(255) DEFAULT NULL,
  `url` varchar(4000) DEFAULT NULL,
  `page_url` varchar(4000) DEFAULT NULL,
  `last_round` int(11) DEFAULT NULL,
  `width` int(11) DEFAULT NULL,
  `height` int(11) DEFAULT NULL,
  `monument_id` varchar(190) DEFAULT NULL,
  `description` text DEFAULT NULL,
  `size` int(11) DEFAULT NULL,
  `author` varchar(255) DEFAULT NULL,
  `mime` varchar(128) DEFAULT NULL,
  `media_type` varchar(32) DEFAULT NULL,
  `deleted_at` timestamp(3) NULL DEFAULT NULL,
  PRIMARY KEY (`page_id`),
  KEY `monument_index` (`monument_id`),
  KEY `images_title_index` (`title`),
  KEY `images_deleted_at_idx` (`deleted_at`),
  KEY `images_page_monument_idx` (`page_id`,`monument_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `images`
--

LOCK TABLES `images` WRITE;
/*!40000 ALTER TABLE `images` DISABLE KEYS */;
/*!40000 ALTER TABLE `images` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `job`
--

DROP TABLE IF EXISTS `job`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `job` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `kind` varchar(64) NOT NULL,
  `contest_id` bigint(20) unsigned DEFAULT NULL,
  `subject_type` varchar(32) DEFAULT NULL,
  `subject_id` bigint(20) DEFAULT NULL,
  `dedupe_key` varchar(190) DEFAULT NULL,
  `status` varchar(16) NOT NULL,
  `params` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`params`)),
  `state` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL CHECK (json_valid(`state`)),
  `progress_done` bigint(20) DEFAULT NULL,
  `progress_total` bigint(20) DEFAULT NULL,
  `progress_unit` varchar(32) DEFAULT NULL,
  `error` text DEFAULT NULL,
  `attempts` int(11) NOT NULL DEFAULT 0,
  `created_by` bigint(20) unsigned DEFAULT NULL,
  `owner` varchar(64) DEFAULT NULL,
  `created_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3),
  `started_at` timestamp(3) NULL DEFAULT NULL,
  `updated_at` timestamp(3) NULL DEFAULT NULL,
  `finished_at` timestamp(3) NULL DEFAULT NULL,
  `heartbeat_at` timestamp(3) NULL DEFAULT NULL,
  `active_dedupe_key` varchar(190) GENERATED ALWAYS AS (if(`status` in ('queued','running'),`dedupe_key`,NULL)) STORED,
  PRIMARY KEY (`id`),
  UNIQUE KEY `job_active_dedupe_uidx` (`active_dedupe_key`),
  KEY `job_status_kind_idx` (`status`,`kind`),
  KEY `job_contest_created_idx` (`contest_id`,`created_at`),
  KEY `job_subject_idx` (`subject_type`,`subject_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `job`
--

LOCK TABLES `job` WRITE;
/*!40000 ALTER TABLE `job` DISABLE KEYS */;
/*!40000 ALTER TABLE `job` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `legacy_password_retired`
--

DROP TABLE IF EXISTS `legacy_password_retired`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `legacy_password_retired` (
  `user_id` bigint(20) unsigned NOT NULL,
  `retired_at` datetime(3) NOT NULL,
  PRIMARY KEY (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `legacy_password_retired`
--

LOCK TABLES `legacy_password_retired` WRITE;
/*!40000 ALTER TABLE `legacy_password_retired` DISABLE KEYS */;
/*!40000 ALTER TABLE `legacy_password_retired` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `legacy_password_retirement`
--

DROP TABLE IF EXISTS `legacy_password_retirement`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `legacy_password_retirement` (
  `id` tinyint(3) unsigned NOT NULL,
  `retire_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `legacy_password_retirement`
--

LOCK TABLES `legacy_password_retirement` WRITE;
/*!40000 ALTER TABLE `legacy_password_retirement` DISABLE KEYS */;
INSERT INTO `legacy_password_retirement` VALUES (1,'2027-02-03 01:43:21.936');
/*!40000 ALTER TABLE `legacy_password_retirement` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `monument`
--

DROP TABLE IF EXISTS `monument`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `monument` (
  `id` varchar(190) DEFAULT NULL,
  `name` varchar(512) NOT NULL,
  `description` varchar(4000) DEFAULT NULL,
  `place` text DEFAULT NULL,
  `photo` varchar(400) DEFAULT NULL,
  `gallery` varchar(400) DEFAULT NULL,
  `page` varchar(400) DEFAULT NULL,
  `typ` varchar(255) DEFAULT NULL,
  `sub_type` varchar(255) DEFAULT NULL,
  `user` varchar(400) DEFAULT NULL,
  `area` varchar(400) DEFAULT NULL,
  `resolution` varchar(400) DEFAULT NULL,
  `lat` varchar(32) DEFAULT NULL,
  `lon` varchar(32) DEFAULT NULL,
  `year` varchar(255) DEFAULT NULL,
  `city` varchar(255) DEFAULT NULL,
  `contest` bigint(20) DEFAULT NULL,
  `adm0` varchar(3) DEFAULT NULL,
  `adm1` varchar(6) DEFAULT NULL,
  UNIQUE KEY `monument_id_unique` (`id`),
  KEY `adm0_index` (`adm0`),
  KEY `adm1_index` (`adm1`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `monument`
--

LOCK TABLES `monument` WRITE;
/*!40000 ALTER TABLE `monument` DISABLE KEYS */;
/*!40000 ALTER TABLE `monument` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `round`
--

DROP TABLE IF EXISTS `round`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `round` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `name` varchar(255) DEFAULT NULL,
  `number` int(11) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `deleted_at` timestamp NULL DEFAULT NULL,
  `contest_id` int(11) DEFAULT NULL,
  `roles` varchar(255) NOT NULL DEFAULT 'jury',
  `rates` int(11) DEFAULT 1,
  `limit_min` int(11) DEFAULT 1,
  `limit_max` int(11) DEFAULT 50,
  `recommended` int(11) DEFAULT NULL,
  `distribution` int(11) DEFAULT 0,
  `active` tinyint(1) DEFAULT NULL,
  `optional_rate` tinyint(1) DEFAULT NULL,
  `jury_org_view` tinyint(1) DEFAULT NULL,
  `min_mpx` int(11) DEFAULT NULL,
  `previous` varchar(255) DEFAULT NULL,
  `prev_selected_by` int(11) DEFAULT NULL,
  `prev_min_avg_rate` decimal(4,2) DEFAULT NULL,
  `category_clause` varchar(16) DEFAULT NULL,
  `category` varchar(4000) DEFAULT NULL,
  `regions` text DEFAULT NULL,
  `min_image_size` int(11) DEFAULT NULL,
  `has_criteria` tinyint(1) DEFAULT NULL,
  `half_star` tinyint(1) DEFAULT NULL,
  `monuments` text DEFAULT NULL,
  `top_images` int(11) DEFAULT NULL,
  `special_nomination` varchar(256) DEFAULT NULL,
  `media_type` varchar(256) DEFAULT NULL,
  `submit_token` varchar(36) DEFAULT NULL,
  `distributing` tinyint(1) NOT NULL DEFAULT 0,
  `freeze_count` int(11) NOT NULL DEFAULT 0,
  `active_before_freeze` tinyint(1) DEFAULT NULL,
  `exclude_coi` tinyint(1) NOT NULL DEFAULT 0,
  `exclude_category` varchar(4000) DEFAULT NULL,
  `top_binary_order` varchar(16) DEFAULT NULL,
  `prev_rejected_by` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `round_submit_token_uidx` (`submit_token`),
  KEY `round_contest_active_idx` (`contest_id`,`active`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `round`
--

LOCK TABLES `round` WRITE;
/*!40000 ALTER TABLE `round` DISABLE KEYS */;
/*!40000 ALTER TABLE `round` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `round_user`
--

DROP TABLE IF EXISTS `round_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `round_user` (
  `user_id` bigint(20) unsigned NOT NULL,
  `round_id` bigint(20) unsigned NOT NULL,
  `role` varchar(255) DEFAULT NULL,
  `active` tinyint(1) DEFAULT NULL,
  UNIQUE KEY `ru_round_user` (`user_id`,`round_id`),
  KEY `idx_round_user_round_active` (`round_id`,`active`),
  CONSTRAINT `FK_round_user_round_id` FOREIGN KEY (`round_id`) REFERENCES `round` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_round_user_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `round_user`
--

LOCK TABLES `round_user` WRITE;
/*!40000 ALTER TABLE `round_user` DISABLE KEYS */;
/*!40000 ALTER TABLE `round_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `session_upgrade_window`
--

DROP TABLE IF EXISTS `session_upgrade_window`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `session_upgrade_window` (
  `id` tinyint(3) unsigned NOT NULL,
  `until_at` datetime(3) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `session_upgrade_window`
--

LOCK TABLES `session_upgrade_window` WRITE;
/*!40000 ALTER TABLE `session_upgrade_window` DISABLE KEYS */;
INSERT INTO `session_upgrade_window` VALUES (1,'2026-12-03 01:43:21.914');
/*!40000 ALTER TABLE `session_upgrade_window` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `user_contest`
--

DROP TABLE IF EXISTS `user_contest`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_contest` (
  `user_id` bigint(20) unsigned NOT NULL,
  `contest_id` bigint(20) unsigned NOT NULL,
  `role` varchar(255) NOT NULL,
  PRIMARY KEY (`user_id`,`contest_id`),
  KEY `idx_user_contest_contest` (`contest_id`,`role`),
  CONSTRAINT `fk_uc_contest` FOREIGN KEY (`contest_id`) REFERENCES `contest_jury` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_uc_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `chk_user_contest_role` CHECK (cast(`role` as char charset binary) in ('no','jury','organizer','admin','board','auditor','staff'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `user_contest`
--

LOCK TABLES `user_contest` WRITE;
/*!40000 ALTER TABLE `user_contest` DISABLE KEYS */;
/*!40000 ALTER TABLE `user_contest` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `users`
--

DROP TABLE IF EXISTS `users`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `fullname` varchar(255) NOT NULL,
  `email` varchar(190) DEFAULT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `deleted_at` timestamp NULL DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `lang` char(10) DEFAULT NULL,
  `wiki_account` varchar(256) DEFAULT NULL,
  `sort` int(11) DEFAULT NULL,
  `is_root` tinyint(1) NOT NULL DEFAULT 0,
  `theme` varchar(8) DEFAULT NULL,
  `email_verified_at` timestamp(3) NULL DEFAULT NULL,
  `wiki_account_verified_at` timestamp(3) NULL DEFAULT NULL,
  `auth_version` int(10) unsigned NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`),
  KEY `idx_users_wiki_account` (`wiki_account`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `users`
--

LOCK TABLES `users` WRITE;
/*!40000 ALTER TABLE `users` DISABLE KEYS */;
/*!40000 ALTER TABLE `users` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `v62_rounds_turned_off`
--

DROP TABLE IF EXISTS `v62_rounds_turned_off`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `v62_rounds_turned_off` (
  `round_id` bigint(20) unsigned NOT NULL,
  `active` tinyint(1) DEFAULT NULL,
  `active_before_freeze` tinyint(1) DEFAULT NULL,
  PRIMARY KEY (`round_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `v62_rounds_turned_off`
--

LOCK TABLES `v62_rounds_turned_off` WRITE;
/*!40000 ALTER TABLE `v62_rounds_turned_off` DISABLE KEYS */;
/*!40000 ALTER TABLE `v62_rounds_turned_off` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vote`
--

DROP TABLE IF EXISTS `vote`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `vote` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `jury_id` int(11) NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `round_id` int(11) DEFAULT NULL,
  `page_id` bigint(20) DEFAULT NULL,
  `rate` int(11) DEFAULT NULL,
  `criteria_id` int(11) DEFAULT NULL,
  `monument_id` varchar(190) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `vote_page_jury_round_uidx` (`page_id`,`jury_id`,`round_id`),
  KEY `vote_rate_idx` (`rate`),
  KEY `vote_round_idx` (`round_id`),
  KEY `vote_jury_id_idx` (`jury_id`),
  KEY `vote_page_id_idx` (`page_id`),
  KEY `vote_round_rate_idx` (`round_id`,`rate`),
  KEY `vote_jury_round_rate_mon_page_idx` (`jury_id`,`round_id`,`rate`,`monument_id`,`page_id`),
  KEY `vote_round_jury_rate_idx` (`round_id`,`jury_id`,`rate`),
  KEY `vote_round_monument_id_idx` (`round_id`,`monument_id`),
  KEY `vote_round_page_rate_idx` (`round_id`,`page_id`,`rate`),
  CONSTRAINT `vote_page_id_fk` FOREIGN KEY (`page_id`) REFERENCES `images` (`page_id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vote`
--

LOCK TABLES `vote` WRITE;
/*!40000 ALTER TABLE `vote` DISABLE KEYS */;
/*!40000 ALTER TABLE `vote` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vote_aside`
--

DROP TABLE IF EXISTS `vote_aside`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `vote_aside` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `jury_id` int(11) NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `round_id` int(11) DEFAULT NULL,
  `page_id` bigint(20) DEFAULT NULL,
  `rate` int(11) DEFAULT NULL,
  `criteria_id` int(11) DEFAULT NULL,
  `monument_id` varchar(190) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `vote_aside_page_jury_round_uidx` (`page_id`,`jury_id`,`round_id`),
  KEY `vote_aside_rate_idx` (`rate`),
  KEY `vote_aside_round_idx` (`round_id`),
  KEY `vote_aside_jury_id_idx` (`jury_id`),
  KEY `vote_aside_page_id_idx` (`page_id`),
  KEY `vote_aside_round_rate_idx` (`round_id`,`rate`),
  KEY `vote_aside_jury_round_rate_mon_page_idx` (`jury_id`,`round_id`,`rate`,`monument_id`,`page_id`),
  KEY `vote_aside_round_jury_rate_idx` (`round_id`,`jury_id`,`rate`),
  KEY `vote_aside_round_monument_id_idx` (`round_id`,`monument_id`),
  KEY `vote_aside_round_page_rate_idx` (`round_id`,`page_id`,`rate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vote_aside`
--

LOCK TABLES `vote_aside` WRITE;
/*!40000 ALTER TABLE `vote_aside` DISABLE KEYS */;
/*!40000 ALTER TABLE `vote_aside` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `vote_orphan`
--

DROP TABLE IF EXISTS `vote_orphan`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `vote_orphan` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `jury_id` int(11) NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `round_id` int(11) DEFAULT NULL,
  `page_id` bigint(20) DEFAULT NULL,
  `rate` int(11) DEFAULT NULL,
  `criteria_id` int(11) DEFAULT NULL,
  `monument_id` varchar(190) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `vote_orphan_page_jury_round_uidx` (`page_id`,`jury_id`,`round_id`),
  KEY `vote_orphan_rate_idx` (`rate`),
  KEY `vote_orphan_round_idx` (`round_id`),
  KEY `vote_orphan_jury_id_idx` (`jury_id`),
  KEY `vote_orphan_page_id_idx` (`page_id`),
  KEY `vote_orphan_round_rate_idx` (`round_id`,`rate`),
  KEY `vote_orphan_jury_round_rate_mon_page_idx` (`jury_id`,`round_id`,`rate`,`monument_id`,`page_id`),
  KEY `vote_orphan_round_jury_rate_idx` (`round_id`,`jury_id`,`rate`),
  KEY `vote_orphan_round_monument_id_idx` (`round_id`,`monument_id`),
  KEY `vote_orphan_round_page_rate_idx` (`round_id`,`page_id`,`rate`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `vote_orphan`
--

LOCK TABLES `vote_orphan` WRITE;
/*!40000 ALTER TABLE `vote_orphan` DISABLE KEYS */;
/*!40000 ALTER TABLE `vote_orphan` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed
