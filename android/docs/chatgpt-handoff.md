# Coach: manual AI handoff

Open **Coach** to copy a personalized prompt, paste it into your preferred AI app,
then paste the reply back into FitNotes. No chatbot, API key, backend or automatic
AI network request is involved.

Choose a workout, training week, progress review or custom question; Gym, Running
or Both; and 7/30/90 days or custom inclusive dates. Goals, equipment, constraints
and available time reuse existing local preferences. **Copy coach prompt** is one
action. **View shared data** shows the exact full clipboard text.

Coach shares compact completed strength results and actual activity summaries.
Private notes, body measurements, photos, raw heart-rate samples, unfinished
workouts and planned targets are omitted. History is never silently shortened.

**Paste AI response** reads the clipboard only when pressed. Manual text entry
is also available. Ordinary advice is displayed without creating a workout.
Structured plans open the editable preview, with explicit exercise identity
review. Import creates prescriptions only and activates one flexible queue.

The public response contract is ordinary JSON: a single session or a title and
ordered `sessions` array. Markdown fences and explanations outside the JSON work.
No marker or schema knowledge is required. Strength uses the existing individual
set-target model and editor; running uses separate optional targets/guidance.
AI numeric weights are omitted unless explicitly accepted; local weight advice
remains available through Train's existing history-based review.

See [the full contract, queue behavior, migration and scope](coach-and-flexible-queue.md).

Legacy saved plan files retain their existing decoder and target semantics.
Existing whole-program JSON/text imports, exercise aliases, backups and actual
workout snapshots remain supported. The old versioned marker is an internal file
compatibility detail, not the Coach clipboard format.
