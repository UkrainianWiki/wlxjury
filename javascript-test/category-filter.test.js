/** @jest-environment jsdom */
const $ = require('jquery');
const initCategoryFilter = require('../public/javascripts/category-filter');

beforeEach(() => {
    document.body.innerHTML = `
        <select id="categoryClause">
            <option value="none">None</option><option value="include">Include</option>
            <option value="exclude">Exclude</option><option value="and">And</option>
            <option value="or">Or</option><option value="minus">Minus</option>
        </select>
        <div id="category-first"><input id="source" name="source"></div>
        <div id="category-second"><input id="excludeCategory" name="excludeCategory"></div>`;
});

test('switching modes changes required operands and preserves entered categories', () => {
    initCategoryFilter($);
    expect($('#category-first').css('display')).toBe('none');
    expect($('#category-second').css('display')).toBe('none');
    expect($('#source').prop('required')).toBe(false);
    expect($('#excludeCategory').prop('required')).toBe(false);

    $('#categoryClause').val('or').trigger('change');
    expect($('#category-first').css('display')).not.toBe('none');
    expect($('#category-second').css('display')).not.toBe('none');
    expect($('#source').prop('required')).toBe(true);
    expect($('#excludeCategory').prop('required')).toBe(true);
    $('#source').val('Category:A');
    $('#excludeCategory').val('Category:B');

    $('#categoryClause').val('exclude').trigger('change');
    expect($('#category-first').css('display')).not.toBe('none');
    expect($('#category-second').css('display')).toBe('none');
    expect($('#source').prop('required')).toBe(true);
    expect($('#excludeCategory').prop('required')).toBe(false);

    $('#categoryClause').val('minus').trigger('change');
    expect($('#excludeCategory').prop('required')).toBe(true);
    expect($('#source').val()).toBe('Category:A');
    expect($('#excludeCategory').val()).toBe('Category:B');
});

test('an existing intersection initially shows both operands without changing their values', () => {
    $('#categoryClause').val('and');
    $('#source').val('Category:A').prop('readonly', true);
    $('#excludeCategory').val('Category:B').prop('readonly', true);
    initCategoryFilter($);
    expect($('#category-first').css('display')).not.toBe('none');
    expect($('#category-second').css('display')).not.toBe('none');
    expect($('#source').val()).toBe('Category:A');
    expect($('#excludeCategory').val()).toBe('Category:B');
    expect($('#source').prop('readonly')).toBe(true);
});
