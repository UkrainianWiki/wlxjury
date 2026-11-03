
if(typeof g_ugFunctions != "undefined")
	g_ugFunctions.registerTheme("tiles");
else 
	jQuery(document).ready(function(){g_ugFunctions.registerTheme("tiles")});


/**
 * Grid gallery theme
 */
function UGTheme_tiles(){

	var t = this;
	var g_gallery = new UniteGalleryMain(), g_objGallery, g_objects, g_objWrapper; 
	var g_tiles = new UGTiles(), g_lightbox = new UGLightbox(), g_objPreloader, g_objTilesWrapper;
	var g_functions = new UGFunctions(), g_objTileDesign = new UGTileDesign();
	
	var g_options = {
			theme_enable_preloader: true,		//enable preloader circle
			theme_preloading_height: 200,		//the height of the preloading div, it show before the gallery
			theme_preloader_vertpos: 100,		//the vertical position of the preloader
			theme_gallery_padding: 0,			//the horizontal padding of the gallery from the sides
			theme_appearance_order: "normal",	//normal, shuffle, keep - the appearance order of the tiles. applying only to columns type
			theme_auto_open:null				//auto open lightbox at start
	};
	
	var g_defaults = {
			gallery_width: "100%"
	};
	
	//temp variables
	var g_temp = {
			showPreloader: false
	};

	/**
	 * Init the theme
	 */
	function initTheme(gallery, customOptions){
		
		g_gallery = gallery;
		
		//set default options
		g_options = jQuery.extend(g_options, g_defaults);
		
		//set custom options
		g_options = jQuery.extend(g_options, customOptions);
		
		modifyOptions();
		
		//set gallery options
		g_gallery.setOptions(g_options);
		
		g_gallery.setFreestyleMode();
		
		g_objects = gallery.getObjects();
		
		//get some objects for local use
		g_objGallery = jQuery(gallery);		
		g_objWrapper = g_objects.g_objWrapper;
		
		//init objects
		g_tiles.init(gallery, g_options);
		g_lightbox.init(gallery, g_options);
		
		g_objTileDesign = g_tiles.getObjTileDesign();

		
	}
	
	
	/**
	 * modift options
	 */
	function modifyOptions(){
		
		if(g_options.theme_enable_preloader == true)
			g_temp.showPreloader = true;
		
		switch(g_options.theme_appearance_order){
			default:
			case "normal":
			break;
			case "shuffle":
				g_gallery.shuffleItems();
			break;
			case "keep":
				g_options.tiles_keep_order = true;
			break;
		}
		
	}

	
	/**
	 * set gallery html elements
	 */
	function setHtml(){
		
		//add html elements
		g_objWrapper.addClass("ug-theme-tiles");
		
		g_objWrapper.append("<div class='ug-tiles-wrapper' style='position:relative'></div>");
		
		//add preloader
		if(g_temp.showPreloader == true){
			g_objWrapper.append("<div class='ug-tiles-preloader ug-preloader-trans'></div>");
			g_objPreloader = g_objWrapper.children(".ug-tiles-preloader");
			g_objPreloader.fadeTo(0,0);
		}
		
		g_objTilesWrapper = g_objWrapper.children(".ug-tiles-wrapper");
		
		//set padding
		if(g_options.theme_gallery_padding)
			g_objWrapper.css({
				"padding-left":g_options.theme_gallery_padding+"px",
				"padding-right":g_options.theme_gallery_padding+"px"
			});
		
		g_tiles.setHtml(g_objTilesWrapper);
		g_lightbox.putHtml();
	}
	
	/**
	 * actually run the theme
	 */
	function actualRun(){
		
		//set preloader mode
		if(g_objPreloader){
			g_objPreloader.fadeTo(0,1);
			g_objWrapper.height(g_options.theme_preloading_height);
			g_functions.placeElement(g_objPreloader, "center", g_options.theme_preloader_vertpos);
		}
		
		initEvents();
		
		g_tiles.run();
		g_lightbox.run();
		
	}

	
	/**
	 * run the theme
	 */
	function runTheme(){
		
		setHtml();
		
		actualRun();
		
	}
	
	
	/**
	 * init size of the thumbs panel
	 */
	function initThumbsPanel(){
		
		//set size:
		var objGallerySize = g_gallery.getSize();
			
		if(g_temp.isVertical == false)			
			g_objPanel.setWidth(objGallerySize.width);
		else
			g_objPanel.setHeight(objGallerySize.height);
		
		g_objPanel.run();
	}
	
	
	/**
	 * on tile click - open lightbox
	 */
	function onTileClick(data, objTile){
		
		objTile = jQuery(objTile);		
		
		var objItem = g_objTileDesign.getItemByTile(objTile);

		var rate = objItem.rate;
		//a round's tile opens the large view with its rate; a contest file's (no data-rate),
		//its large view's url as it is, with the list's filters
		var url = (rate === undefined) ? objItem.largeUrl : objItem.largeUrl.split('?')[0] + '?rate=' + rate;

		window.open(url, "_self");
	}

	/**
	 * on tile click - open lightbox
	 */
	function onSelectClick(data, objTile){

		objTile = jQuery(objTile);

		var objItem = g_objTileDesign.getItemByTile(objTile);

		if (objItem.rate != 1) {
			serviceCall(objItem, 1, objTile)
		} else {
			serviceCall(objItem, 0, objTile)
		}
	}

	/**
	 * on tile click - open lightbox
	 */
	function onRejectClick(data, objTile) {

		objTile = jQuery(objTile);

		var objItem = g_objTileDesign.getItemByTile(objTile);

		if (objItem.rate != -1) {
			serviceCall(objItem, -1, objTile)
		} else {
			serviceCall(objItem, 0, objTile)
		}
	}

	/**
	 * the session's CSRF token, which the gallery page renders (data-csrf-token)
	 */
	function csrfToken() {
		var holder = document.querySelector("[data-csrf-token]");
		return holder ? holder.getAttribute("data-csrf-token") : "";
	}

	function serviceCall(objItem, rate, objTile) {

		//a vote is a POST with the page's CSRF token in Play's header (play.filters.csrf.header.name)
		var jqxhr = $.ajax({
			type: "POST",
			url: "/rate/round/" + objItem.roundId +"/pageid/" + objItem.pageId + "/select/" + rate,
			headers: { "Csrf-Token": csrfToken() }
		}).done(function() {

			var border = 3;
			var color;

			if (rate == 1) color = "limegreen";
			else if (rate == -1) color = "brown";
			else if (rate == 0) color = "white";

			objItem.rate = rate;

			var objCss = {};

			objCss["border-width"] = border + "px";
			objCss["border-style"] = "solid";
			objCss["border-color"] = color;

			objTile.css(objCss);

		}).fail(function(jqXHR) {
			// why, in the page's language (vote-errors.js); signed out, on to the login page
			var texts = voteErrorTexts(document.querySelector("[data-csrf-token]"));
			alert(voteErrorMessage(jqXHR.status, jqXHR.responseJSON || null, texts));
			if (jqXHR.status === 401 && texts.loginUrl) window.location.href = texts.loginUrl;
		});
	}


		/**
	 * before items request: hide items, show preloader
	 */
	function onBeforeReqestItems(){
				
		g_objTilesWrapper.hide();
		
		if(g_objPreloader){
			g_objPreloader.show();
		
			var preloaderSize = g_functions.getElementSize(g_objPreloader);
			var galleryHeight = preloaderSize.bottom + 30;
			
			g_objWrapper.height(galleryHeight);
		}
		
	}

	/**
	 * open lightbox at start if needed
	 */
	function onLightboxInit(){

		if(g_options.theme_auto_open !== null){
			g_lightbox.open(g_options.theme_auto_open);
			g_options.theme_auto_open = null;
		}
		
	}
	
	
	/**
	 * init buttons functionality and events
	 */
	function initEvents(){
		
		//remove preloader on tiles first placed
		if(g_objPreloader){
			jQuery(g_tiles).on(g_tiles.events.TILES_FIRST_PLACED, function(){
				g_objWrapper.height("auto");
				g_objPreloader.hide();
			});			
		}
		
		jQuery(g_objTileDesign).on(g_objTileDesign.events.TILE_CLICK, onTileClick);
		jQuery(g_objTileDesign).on(g_objTileDesign.events.SELECT_CLICK, onSelectClick);
		jQuery(g_objTileDesign).on(g_objTileDesign.events.REJECT_CLICK, onRejectClick);
		
		g_objGallery.on(g_gallery.events.GALLERY_BEFORE_REQUEST_ITEMS, onBeforeReqestItems);

		jQuery(g_lightbox).on(g_lightbox.events.LIGHTBOX_INIT, onLightboxInit);

	}
	
	
	/**
	 * destroy the theme
	 */
	this.destroy = function(){
				
		jQuery(g_objTileDesign).off(g_objTileDesign.events.TILE_CLICK);
		jQuery(g_tiles).off(g_tiles.events.TILES_FIRST_PLACED);
		
		g_objGallery.off(g_gallery.events.GALLERY_BEFORE_REQUEST_ITEMS);

		jQuery(g_lightbox).off(g_lightbox.events.LIGHTBOX_INIT);
		
		g_tiles.destroy();
		g_lightbox.destroy();
	}
	
	
	/**
	 * run the theme setting
	 */
	this.run = function(){
		
		runTheme();
	}
	
	
	/**
	 * init 
	 */
	this.init = function(gallery, customOptions){
				
		initTheme(gallery, customOptions);
		
	}
	
	
}
