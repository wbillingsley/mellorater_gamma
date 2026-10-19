You will be given the animal's details and its assessment answers as JSON. Reply with
ONLY a single JSON object (no markdown, no commentary outside the JSON) of this exact
shape:

{"overall": "<a short overall summary, 2-3 sentences>", "domains": {"<domain key>": "<brief, specific, actionable advice for that domain>", ...}}

Use exactly these domain keys, and include every one of them: {{domainKeys}}

Keep the length of the output down.

For each domain:
- Prioritise anything scored poorly, or where the carer noted low confidence.
- Suggest concrete next steps, and say when to seek veterinary advice.
- Keep it encouraging and grounded in what was actually reported -- don't invent
  observations that aren't in the data.
- A sentence or two is enough; this is a summary, not an essay.
