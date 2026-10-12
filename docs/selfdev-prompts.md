# Coding workflow: первый проверенный сценарий

Текущие launch/configuration и runnable gallery — в [user guide](user-guide.md).
Для демонстрации read/search/check/edit/check не требуется настоящая модель:

```sh
python3 scripts/quality/user_examples.py --only coding
```

Это выполняет реальную цепочку tools через native CLI, изменяет копию fixture и
проверяет `cmp` exit 1 → edit с SHA-256 → `cmp` exit 0. Prompt fixture:

> Fix the greeting typo and verify it against expected.txt.

Tool decisions задаёт локальный Scala stand, поэтому это проверка механики.
Произвольный prompt модели не гарантирует этот результат. Для работы со своей
моделью нужны endpoint/model и env/helper credential; настройки перечислены в
user guide. Новые задачи и реальные изменения собственного Raider checkout
получают отдельные карточки с правилами репозитория и проверкой результата.

`coder` использует реальные `fs_read`, `fs_search`, `fs_edit`, `fs_patch`, `fs_tree`
и `proc_run`. Файловые операции проверяют workspace и SHA-256, process tool получает
structured argv. Доверенный repo command имеет полномочия процесса и может делать
собственные side effects; workspace/root policy не является его sandbox.

Для расследования результата используйте `run.json`, `transcript.json`,
`events.jsonl`, `summary.md` и lineage outputs из выведенного CLI run directory.
