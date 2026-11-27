/** @jest-environment jsdom */
const $ = require('jquery');
const initCategoryFilter = require('../public/javascripts/category-filter');

/** The round form's category boxes as imageFiltering.scala.html renders them, `checked` chosen. */
function render(checked, { open = false, disabled = false } = {}) {
    const choice = (value, secondLabel, text) => `
        <div class="form-check">
            <input class="form-check-input" type="radio" name="categoryCombine" id="categoryCombine-${value}"
                value="${value}" data-second-label="${secondLabel}"${value === checked ? ' checked' : ''}${disabled ? ' disabled' : ''}>
            <label class="form-check-label" for="categoryCombine-${value}">${text}</label>
        </div>`;
    const labels = { minus: 'Leave out files in category', and: '…and also in category', or: '…or in category' };
    document.body.innerHTML = `
        <div id="category-first">
            <label for="source">Only files in category</label>
            <input id="source" name="source" aria-describedby="source_help">
            <div class="form-text" id="source_help">Directly in this category</div>
        </div>
        <div id="category-second">
            <label class="wlx-field-label col-form-label" for="excludeCategory"><span>${labels[checked]}</span></label>
            <input id="excludeCategory" name="excludeCategory">
        </div>
        <details id="category-more"${open ? ' open' : ''}>
            <summary>More category options</summary>
            <fieldset id="category-combine">
                ${choice('minus', labels.minus, 'Leave its files out')}
                ${choice('and', labels.and, 'Files must be in both categories')}
                ${choice('or', labels.or, 'Files may be in either category')}
            </fieldset>
        </details>`;
}

const secondLabel = () => $('label[for="excludeCategory"]').text();
const choose = (value) => $(`#categoryCombine-${value}`).prop('checked', true).trigger('change');

test('the second box is "Leave out files in category" by default, More closed', () => {
    render('minus');
    initCategoryFilter($);
    expect(secondLabel()).toBe('Leave out files in category');
    expect($('#category-more').prop('open')).toBe(false);
});

test('the choice under More renames the second box and keeps the categories entered', () => {
    render('minus');
    initCategoryFilter($);
    $('#source').val('Category:A');
    $('#excludeCategory').val('Category:B');

    choose('and');
    expect(secondLabel()).toBe('…and also in category');
    expect($('#category-second label > span').text()).toBe('…and also in category');
    choose('or');
    expect(secondLabel()).toBe('…or in category');
    choose('minus');
    expect(secondLabel()).toBe('Leave out files in category');

    expect($('#source').val()).toBe('Category:A');
    expect($('#excludeCategory').val()).toBe('Category:B');
    // still the box's one label, and the first box's hint still describes it
    expect($('label[for="excludeCategory"]').length).toBe(1);
    expect($('#source').attr('aria-describedby')).toBe('source_help');
});

test.each(['and', 'or'])('a form with "%s" chosen opens More and names the second box', (value) => {
    // as a browser restoring the form's state after Back can leave it: the choice checked, More closed
    render('minus');
    $(`#categoryCombine-${value}`).prop('checked', true);
    initCategoryFilter($);
    expect($('#category-more').prop('open')).toBe(true);
    expect(secondLabel()).toBe(value === 'and' ? '…and also in category' : '…or in category');
});

test('a stored "and" round rendered open and read only keeps its choice, label and values', () => {
    render('and', { open: true, disabled: true });
    $('#source').val('Category:A').prop('readonly', true);
    $('#excludeCategory').val('Category:B').prop('readonly', true);
    initCategoryFilter($);
    expect($('#category-more').prop('open')).toBe(true);
    expect($('input[name="categoryCombine"]:checked').val()).toBe('and');
    expect(secondLabel()).toBe('…and also in category');
    expect($('#source').val()).toBe('Category:A');
    expect($('#excludeCategory').val()).toBe('Category:B');
    expect($('#source').prop('readonly')).toBe(true);
});

test('choosing the default again does not close More', () => {
    render('or', { open: true });
    initCategoryFilter($);
    choose('minus');
    expect($('#category-more').prop('open')).toBe(true);
    expect(secondLabel()).toBe('Leave out files in category');
});

test('a page without the category boxes is left alone', () => {
    document.body.innerHTML = '<input id="source">';
    expect(() => initCategoryFilter($)).not.toThrow();
});
