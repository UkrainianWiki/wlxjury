/**
 * @jest-environment jsdom
 */
"use strict";

// gallery-init.js: the justified tiles' options from #gallery's attributes (views.GalleryTiles),
// as main.scala.html's inline setup had them before.

const { options, init } = require("../public/javascripts/gallery-init");

function gallery(attrs) {
    document.body.innerHTML = `<div id="gallery" ${attrs}></div>`;
    return document.getElementById("gallery");
}

describe("options", () => {
    test("a juror's binary round: votes on the tiles, thumbs, no stars", () => {
        const o = options(gallery('data-jury="true" data-binary="true"'));
        expect(o.jury).toBe(true);
        expect(o.binary).toBe(true);
        expect(o.wlx_stars).toBeNull();
        expect(o.gallery_theme).toBe("tiles");
        expect(o.tiles_type).toBe("justified");
        expect(o.tiles_justified_row_height).toBe(200);
        expect(o.tile_enable_background).toBe(false);
    });

    test("a rated round's stars, from data-stars", () => {
        const o = options(gallery('data-jury="true" data-binary="false" data-stars="{&quot;number&quot;:5,&quot;half&quot;:true}"'));
        expect(o.binary).toBe(false);
        expect(o.wlx_stars).toEqual({ number: 5, half: true });
    });

    test("one juror's votes shown read-only, from data-view-votes; none without it", () => {
        expect(options(gallery('data-jury="false" data-binary="true" data-view-votes="{&quot;binary&quot;:true}"')).wlx_view_votes)
            .toEqual({ binary: true });
        expect(options(gallery('data-jury="true" data-binary="true"')).wlx_view_votes).toBeNull();
        expect(options(gallery('data-view-votes="{binary"')).wlx_view_votes).toBeNull();
    });

    test("no votes without the attributes, and no stars from broken JSON", () => {
        const o = options(gallery('data-stars="{number: 5"'));
        expect(o.jury).toBe(false);
        expect(o.binary).toBe(false);
        expect(o.wlx_stars).toBeNull();
    });
});

describe("init", () => {
    afterEach(() => { delete window.jQuery; });

    test("starts unitegallery on #gallery with its options", () => {
        const calls = [];
        const $ = el => ({ unitegallery: opts => calls.push([el, opts]) });
        $.fn = { unitegallery() {} };
        window.jQuery = $;
        const el = gallery('data-jury="false" data-binary="false"');
        expect(init(document)).not.toBeNull();
        expect(calls).toHaveLength(1);
        expect(calls[0][0]).toBe(el);
        expect(calls[0][1].jury).toBe(false);
    });

    test("does nothing on a page without #gallery, or without unitegallery", () => {
        document.body.innerHTML = "<p>no tiles</p>";
        window.jQuery = Object.assign(() => { throw new Error("called"); }, { fn: { unitegallery() {} } });
        expect(init(document)).toBeNull();
        gallery("");
        window.jQuery = Object.assign(() => { throw new Error("called"); }, { fn: {} });
        expect(init(document)).toBeNull();
    });
});
