# Swap an exercise

In the exercise logger, open the overflow menu, choose **Swap exercise**, search
for a replacement, select it, and tap **Swap**. Back cancels without saving.

The swap applies only to this workout. Remaining planned sets retain reps, rest,
warm-up flags, skipped state and superset rounds. Exercise-specific notes, loads
and effort targets are cleared; choose the replacement's weights. Saved routines
and earlier workouts remain unchanged.

Already logged sets and their linked targets stay under the original exercise.
The replacement appears immediately after it; an exercise with no logged sets is
replaced in place. Source/target validation and mutation share one transaction.
No database schema change is required.
