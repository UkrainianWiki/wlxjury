/* The server validates the same required operands; hiding fields never erases input. */
function initCategoryFilter($) {
    var operation = $('#categoryClause');
    if (!operation.length) return;
    function update() {
        var value = operation.val();
        var firstRequired = value !== 'none' && value !== '';
        var secondRequired = value === 'and' || value === 'or' || value === 'minus';
        $('#category-first').toggle(firstRequired);
        $('#category-second').toggle(secondRequired);
        $('#source').prop('required', firstRequired);
        $('#excludeCategory').prop('required', secondRequired);
    }
    operation.on('change', update);
    update();
}

if (typeof module !== 'undefined' && module.exports) {
    module.exports = initCategoryFilter;
} else {
    jQuery(function () { initCategoryFilter(jQuery); });
}
