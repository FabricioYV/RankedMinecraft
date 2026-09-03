# Guía de contribución

## Flujo de ramas

`master` es la rama estable (la que corre en producción). Todo cambio nuevo se
desarrolla en una rama corta creada desde `master` y se integra mediante Pull
Request — nada de commits directos a `master`.

### Nomenclatura

| Prefijo | Uso | Ejemplo |
|---|---|---|
| `feature/` | Funcionalidad nueva | `feature/veto-mejorado` |
| `fix/` | Corrección de un bug | `fix/requeue-desconexion` |
| `chore/` | Mantenimiento, config, dependencias | `chore/actualizar-gitignore` |
| `refactor/` | Reorganizar código sin cambiar comportamiento | `refactor/split-matchfinisher` |

### Pasos

1. `git checkout master && git pull`
2. `git checkout -b feature/lo-que-sea`
3. Commits pequeños y descriptivos.
4. Push y abrir un Pull Request contra `master`.
5. Al menos una revisión antes de mergear.
6. Borrar la rama una vez mergeada (`git push origin --delete feature/lo-que-sea`).

### Por qué

Hasta ahora se venía trabajando con commits y merges directos a `master`
entre varios colaboradores, lo que generaba un historial difícil de seguir.
Pasar por PR obliga a revisar el cambio antes de que llegue a producción y
deja trazabilidad clara de qué se hizo y por qué.
