/* The round form's category boxes (round/edit/imageFiltering.scala.html): the choice under
   "More category options" says what the second box does, and the box's label follows it. More
   opens for a choice other than the default (as a browser restoring the form after Back can leave
   it). Without this script the server reads the choice all the same; only the label stays as the
   page came. The label is found in the box's wrapper: Tom Select points its `for` at its own field. */
function initCategoryFilter($) {
    var choices = $('input[name="categoryCombine"][type="radio"]');
    var label = $('#category-second label').first();
    if (!choices.length || !label.length) return;
    var more = $('#category-more');
    function update() {
        var chosen = choices.filter(':checked');
        var text = chosen.attr('data-second-label');
        if (text) {
            var labelText = label.children('span').first();
            (labelText.length ? labelText : label).text(text);
        }
        if (chosen.length && chosen.val() !== 'minus') more.prop('open', true);
    }
    choices.on('change', update);
    update();
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = initCategoryFilter;
} else {
    jQuery(function () { initCategoryFilter(jQuery); });
}
