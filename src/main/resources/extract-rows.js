(rows, selectors) => rows.map(row => {
  const one = (selector, field) => {
    const nodes = row.querySelectorAll(selector);
    if (nodes.length !== 1) throw new Error(`${field}: expected one element, found ${nodes.length}`);
    return nodes[0];
  };
  try {
    if (selectors.liveMarker && row.querySelector(selectors.liveMarker)) return {live: true};
    const start = one(selectors.startTime, 'startTime');
    const link = one(selectors.eventLink, 'eventLink');
    return {
      homeTeam: one(selectors.homeTeam, 'homeTeam').innerText.trim(),
      awayTeam: one(selectors.awayTeam, 'awayTeam').innerText.trim(),
      startTime: selectors.startTimeAttribute
        ? (start.getAttribute(selectors.startTimeAttribute) || '') : start.innerText.trim(),
      url: link.href || '',
      homePrice: one(selectors.homePrice, 'homePrice').innerText.trim(),
      drawPrice: one(selectors.drawPrice, 'drawPrice').innerText.trim(),
      awayPrice: one(selectors.awayPrice, 'awayPrice').innerText.trim(),
      live: false
    };
  } catch (error) { return {error: error.message}; }
})
