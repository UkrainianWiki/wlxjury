-- Production's jury schema at V53, MyISAM (structure only, from an anonymised copy of the
-- production database, 2026-10): the starting point of V55_1ConvertToInnoDbSpec.
-- selection_backup stands for the legacy tables the conversion leaves alone.
CREATE TABLE `users` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `fullname` varchar(255) NOT NULL,
  `email` varchar(255) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `deleted_at` timestamp NULL DEFAULT NULL,
  `password` varchar(255) NOT NULL,
  `roles` varchar(255) NOT NULL DEFAULT 'jury',
  `contest_id` int(11) DEFAULT NULL,
  `lang` char(10) DEFAULT NULL,
  `wiki_account` varchar(256) DEFAULT NULL,
  `sort` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `id` (`id`),
  UNIQUE KEY `email` (`email`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
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
  UNIQUE KEY `id` (`id`),
  KEY `FK_contest_category` (`category_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
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
  `previous` varchar(255) DEFAULT NULL,
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
  PRIMARY KEY (`id`),
  UNIQUE KEY `id` (`id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `round_user` (
  `user_id` bigint(20) unsigned NOT NULL,
  `round_id` bigint(20) unsigned NOT NULL,
  `role` varchar(255) DEFAULT NULL,
  `active` tinyint(1) DEFAULT NULL,
  UNIQUE KEY `ru_round_user` (`user_id`,`round_id`),
  KEY `FK_round_user_round_id` (`round_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `category` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `id` (`id`),
  UNIQUE KEY `title` (`title`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `category_members` (
  `category_id` bigint(20) unsigned NOT NULL,
  `page_id` bigint(20) NOT NULL,
  UNIQUE KEY `cm_category_page` (`category_id`,`page_id`),
  KEY `FK_category_member_page_id` (`page_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `comment` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `username` varchar(255) NOT NULL,
  `round_id` int(11) DEFAULT NULL,
  `created_at` text DEFAULT NULL,
  `body` text NOT NULL,
  `room` bigint(20) NOT NULL DEFAULT 1,
  `contest_id` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `FK_comment_contest` (`contest_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `criteria` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `round` int(11) DEFAULT NULL,
  `name` varchar(255) NOT NULL,
  `contest` int(11) DEFAULT NULL,
  UNIQUE KEY `id` (`id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `criteria_rate` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `selection` int(11) NOT NULL,
  `criteria` int(11) NOT NULL,
  `rate` int(11) NOT NULL,
  UNIQUE KEY `id` (`id`),
  KEY `criteria_selection_index` (`selection`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `monument` (
  `id` varchar(11) NOT NULL DEFAULT '',
  `name` varchar(512) NOT NULL,
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
  `contest` bigint(20) DEFAULT 14,
  `adm0` varchar(3) DEFAULT NULL,
  `adm1` varchar(6) DEFAULT NULL,
  `description` varchar(1) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `monument_id_unique` (`id`),
  KEY `adm0_index` (`adm0`),
  KEY `adm1_index` (`adm1`),
  KEY `monument_id_adm0_index` (`id`,`adm0`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `images` (
  `page_id` bigint(20) NOT NULL,
  `contest` bigint(20) NOT NULL DEFAULT 0,
  `title` varchar(255) DEFAULT NULL,
  `url` varchar(4000) DEFAULT NULL,
  `page_url` varchar(4000) DEFAULT NULL,
  `last_round` int(11) DEFAULT NULL,
  `width` int(11) DEFAULT NULL,
  `height` int(11) DEFAULT NULL,
  `monument_id` varchar(255) DEFAULT NULL,
  `description` mediumtext DEFAULT NULL,
  `size` int(11) DEFAULT NULL,
  `author` varchar(255) DEFAULT NULL,
  `mime` varchar(128) DEFAULT NULL,
  PRIMARY KEY (`page_id`),
  KEY `monument_index` (`monument_id`(250)),
  KEY `images_title_index` (`title`(250))
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE `selection` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `page_id` bigint(20) DEFAULT NULL,
  `rate` int(11) DEFAULT NULL,
  `round_id` int(11) DEFAULT NULL,
  `jury_id` int(11) DEFAULT NULL,
  `criteria_id` int(11) DEFAULT NULL,
  `created_at` timestamp NULL DEFAULT NULL,
  `deleted_at` timestamp NULL DEFAULT NULL,
  `monument_id` varchar(190) DEFAULT NULL,
  UNIQUE KEY `id` (`id`),
  UNIQUE KEY `selection_page_jury_round_uidx` (`page_id`,`jury_id`,`round_id`),
  KEY `selection_rate_index` (`rate`),
  KEY `selection_round_index` (`round_id`),
  KEY `selection_jury_id_index` (`jury_id`),
  KEY `selection_page_id_index` (`page_id`),
  KEY `idx_selection_round_rate` (`round_id`,`rate`),
  KEY `idx_selection_jury_round_rate_mon_page` (`jury_id`,`round_id`,`rate`,`monument_id`,`page_id`),
  KEY `idx_selection_round_jury_rate` (`round_id`,`jury_id`,`rate`),
  KEY `idx_selection_round_monument_id` (`round_id`,`monument_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
CREATE TABLE `selection_backup` (
  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,
  `created_at` timestamp NOT NULL DEFAULT current_timestamp() ON UPDATE current_timestamp(),
  `deleted_at` timestamp NULL DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `page_id` bigint(20) DEFAULT NULL,
  `rate` int(11) DEFAULT NULL,
  `round` int(11) DEFAULT NULL,
  `jury_id` int(11) DEFAULT NULL,
  UNIQUE KEY `id` (`id`),
  KEY `selection_rate_index` (`rate`),
  KEY `selection_round_index` (`round`),
  KEY `selection_jury_id_index` (`jury_id`),
  KEY `selection_page_id_index` (`page_id`)
) ENGINE=MyISAM DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_general_ci;
