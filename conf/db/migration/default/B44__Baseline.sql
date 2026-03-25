/*M!999999\- enable the sandbox mode */ 
-- MariaDB dump 10.19  Distrib 10.6.22-MariaDB, for debian-linux-gnu (aarch64)
--
-- Host: localhost    Database: wlxjury
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
-- Table structure for table `category`
--

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
-- Table structure for table `category_members`
--

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
-- Table structure for table `comment`
--

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
  UNIQUE KEY `id` (`id`),
  KEY `FK_comment_contest` (`contest_id`),
  CONSTRAINT `FK_comment_contest` FOREIGN KEY (`contest_id`) REFERENCES `contest_jury` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `contest_jury`
--

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
-- Table structure for table `criteria`
--

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
-- Table structure for table `criteria_rate`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `criteria_rate` (
  `id` bigint(20) unsigned NOT NULL,
  `selection` int(11) NOT NULL,
  `criteria` int(11) NOT NULL,
  `rate` int(11) NOT NULL,
  UNIQUE KEY `id` (`id`),
  KEY `criteria_selection_index` (`selection`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `images`
--

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
  PRIMARY KEY (`page_id`),
  KEY `monument_index` (`monument_id`),
  KEY `images_title_index` (`title`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `monument`
--

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
  KEY `monument_id_index` (`id`),
  KEY `adm0_index` (`adm0`),
  KEY `adm1_index` (`adm1`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `round_user`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `round_user` (
  `user_id` bigint(20) unsigned NOT NULL,
  `round_id` bigint(20) unsigned NOT NULL,
  `role` varchar(255) DEFAULT NULL,
  `active` tinyint(1) DEFAULT NULL,
  UNIQUE KEY `ru_round_user` (`user_id`,`round_id`),
  KEY `FK_round_user_round_id` (`round_id`),
  CONSTRAINT `FK_round_user_round_id` FOREIGN KEY (`round_id`) REFERENCES `rounds` (`id`) ON DELETE CASCADE,
  CONSTRAINT `FK_round_user_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `rounds`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `rounds` (
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
  `previous` int(11) DEFAULT NULL,
  `prev_selected_by` int(11) DEFAULT NULL,
  `prev_min_avg_rate` decimal(4,2) DEFAULT NULL,
  `category_clause` int(11) DEFAULT NULL,
  `category` varchar(4000) DEFAULT NULL,
  `regions` text DEFAULT NULL,
  `min_image_size` int(11) DEFAULT NULL,
  `has_criteria` tinyint(1) DEFAULT NULL,
  `half_star` tinyint(1) DEFAULT NULL,
  `monuments` text DEFAULT NULL,
  `top_images` int(11) DEFAULT NULL,
  `special_nomination` varchar(256) DEFAULT NULL,
  `media_type` varchar(256) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `selection`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `selection` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `jury_id` int(11) NOT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `round_id` int(11) DEFAULT NULL,
  `page_id` bigint(20) DEFAULT NULL,
  `rate` int(11) DEFAULT NULL,
  `criteria_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `selection_rate_index` (`rate`),
  KEY `selection_round_index` (`round_id`),
  KEY `selection_jury_id_index` (`jury_id`),
  KEY `selection_page_id_index` (`page_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `users`
--

/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `users` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `fullname` varchar(255) NOT NULL,
  `email` varchar(190) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp(),
  `deleted_at` timestamp NULL DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `roles` varchar(255) DEFAULT NULL,
  `contest_id` int(11) DEFAULT NULL,
  `lang` char(10) DEFAULT NULL,
  `wiki_account` varchar(256) DEFAULT NULL,
  `sort` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-03-25  8:17:50
