-- report mensile
SELECT cliente, SUM(importo) AS totale
FROM fatture
WHERE data >= '2026-09-01'
GROUP BY cliente
ORDER BY totale DESC;
