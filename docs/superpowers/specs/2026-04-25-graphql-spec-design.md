# GraphQL API Specification — Design

**Date:** 2026-04-25
**Branch:** graphql
**Status:** Approved for implementation

---

## Overview

Replace the ad-hoc Tapir REST endpoints and Play HTML-controller responses with a single, schema-first GraphQL API that serves both the React UI and external clients. Sangria is the execution engine; the endpoint lives on Pekko HTTP port 9001 alongside the existing Tapir/Swagger routes.

---

## Architecture

```
Client (React SPA / external tools)
        │
        ▼
Pekko HTTP  :9001
  ├── /api/...         (existing Tapir REST + Swagger — unchanged)
  └── /graphql         (new: POST for queries/mutations, WebSocket for subscriptions)
        │
        ▼
GraphQL Layer  app/graphql/
  ├── schema.graphql          ← SDL — the canonical contract
  ├── SchemaDefinition.scala  ← Sangria schema wired from SDL + type mappings
  ├── GraphQLContext.scala    ← per-request: currentUser, auth method
  ├── GraphQLRoute.scala      ← Pekko HTTP handler (auth middleware + Executor)
  └── resolvers/
        ├── ContestResolver.scala
        ├── RoundResolver.scala
        ├── ImageResolver.scala
        ├── UserResolver.scala
        ├── CommentResolver.scala
        └── MonumentResolver.scala
        │
        ▼
Existing services/  (ContestService, RoundService, GalleryService, ImageService,
                     UserService, MonumentService)
```

The existing Play app (port 9000) remains untouched for the HTML UI during transition. Over time, HTML controllers are replaced by the React SPA consuming this GraphQL API.

---

## Dependencies

Add to `build.sbt` (verify latest stable at implementation time):

```scala
"org.sangria-graphql" %% "sangria"           % "4.1.0",
"org.sangria-graphql" %% "sangria-play-json" % "2.0.2",
"com.github.jwt-scala" %% "jwt-play-json"    % "10.0.1"  // JWT verification
```

---

## Authentication

Auth middleware in `GraphQLRoute` resolves the caller before Sangria runs:

1. **Play-proxied session** (`X-Authenticated-User` trusted internal header): Play's existing HTML routes proxy `/graphql` to Pekko HTTP and inject the authenticated username as a trusted header — only trusted when the request arrives from localhost. DB lookup → `User`.
2. **Bearer JWT** (`Authorization: Bearer <token>`): HS256, secret from `application.conf` (`graphql.jwt.secret`), `userId` claim → DB lookup → `User`. Used by the React SPA and external clients.
3. **No credential** → `currentUser = None`

The React SPA authenticates via `mutation login` to receive a JWT, then sends it as `Authorization: Bearer` on all subsequent requests directly to port 9001.

A new mutation issues JWT tokens:

```graphql
mutation {
  login(email: String!, password: String!): AuthPayload!
}

type AuthPayload {
  token: String!
  user: User!
}
```

Resolvers call shared helpers in `GraphQLContext`:

```scala
def requireAuth(): User          // raises UNAUTHENTICATED if currentUser is None
def requireRole(role: String): User  // raises FORBIDDEN if role missing
```

Role checks reuse the existing `user.hasRole` / `user.isAdmin` logic unchanged.

---

## SDL Schema

### Types

```graphql
type Contest {
  id: ID!
  name: String!
  year: Int!
  country: String!
  images: String
  currentRound: ID
  monumentIdTemplate: String
  campaign: String
  rounds: [Round!]!
}

type Round {
  id: ID!
  number: Int!
  name: String
  contestId: ID!
  active: Boolean!
  distribution: Int!
  minMpx: Int
  category: String
  regions: String
  mediaType: String
  hasCriteria: Boolean!
  jurors: [User!]!
  images(page: Int, pageSize: Int, rate: Int, region: String): [ImageWithRating!]!
}

type User {
  id: ID!
  fullname: String!
  email: String!
  roles: [String!]!
  contestId: ID
  lang: String
  wikiAccount: String
  active: Boolean
}

type Image {
  pageId: ID!
  title: String!
  url: String
  pageUrl: String
  width: Int!
  height: Int!
  monumentId: String
  author: String
  mime: String
  mpx: Float
  isVideo: Boolean!
}

type Selection {
  id: ID
  pageId: ID!
  juryId: ID!
  roundId: ID!
  rate: Int!
  criteriaId: Int
  monumentId: String
  createdAt: String
}

type ImageWithRating {
  image: Image!
  selections: [Selection!]!
  totalRate: Float!
  rateSum: Int!
  rank: Int
}

type Comment {
  id: ID!
  userId: ID!
  username: String!
  roundId: ID!
  contestId: ID
  imagePageId: ID!
  body: String!
  createdAt: String!
}

type Monument {
  id: ID!
  name: String!
  description: String
  year: String
  city: String
  place: String
  lat: String
  lon: String
  typ: String
  subType: String
  photo: String
  contest: ID
}

type RoundStat {
  roundId: ID!
  totalImages: Int!
  ratedImages: Int!
  jurorStats: [JurorStat!]!
}

type JurorStat {
  user: User!
  rated: Int!
  selected: Int!
}

type AuthPayload {
  token: String!
  user: User!
}
```

### Queries

```graphql
type Query {
  # Contests
  contests: [Contest!]!
  contest(id: ID!): Contest

  # Rounds
  round(id: ID!): Round
  rounds(contestId: ID!): [Round!]!

  # Images / gallery
  images(roundId: ID!, userId: ID, page: Int, pageSize: Int,
         rate: Int, region: String): [ImageWithRating!]!
  image(pageId: ID!): Image

  # Users
  users(contestId: ID): [User!]!
  user(id: ID!): User
  me: User

  # Comments
  comments(roundId: ID!, imagePageId: ID): [Comment!]!

  # Monuments
  monuments(contestId: ID!): [Monument!]!
  monument(id: ID!): Monument

  # Round statistics
  roundStat(roundId: ID!): RoundStat!
}
```

### Mutations

```graphql
type Mutation {
  # Auth
  login(email: String!, password: String!): AuthPayload!

  # Contests
  createContest(input: ContestInput!): Contest!
  updateContest(id: ID!, input: ContestInput!): Contest!
  deleteContest(id: ID!): Boolean!

  # Rounds
  createRound(input: RoundInput!): Round!
  updateRound(id: ID!, input: RoundInput!): Round!
  setActiveRound(id: ID!): Round!

  # Ratings
  rateImage(roundId: ID!, pageId: ID!, rate: Int!, criteriaId: Int): Selection!
  rateImageBulk(roundId: ID!, ratings: [RatingInput!]!): [Selection!]!

  # Users
  createUser(input: UserInput!): User!
  updateUser(id: ID!, input: UserInput!): User!
  resetPassword(id: ID!): Boolean!
  addJurorsToRound(roundId: ID!, userIds: [ID!]!): Round!

  # Comments
  addComment(roundId: ID!, imagePageId: ID!, body: String!): Comment!

  # Images
  setRoundImages(roundId: ID!, category: String!): Boolean!
}
```

### Input Types

```graphql
input ContestInput {
  name: String!
  year: Int!
  country: String!
  images: String
  monumentIdTemplate: String
  campaign: String
}

input RoundInput {
  contestId: ID!
  name: String
  distribution: Int
  minMpx: Int
  category: String
  regions: String
  mediaType: String
  hasCriteria: Boolean
  previousRoundId: ID
  prevSelectedBy: Int
}

input UserInput {
  fullname: String!
  email: String!
  roles: [String!]!
  contestId: ID
  lang: String
  wikiAccount: String
}

input RatingInput {
  pageId: ID!
  rate: Int!
}
```

### Subscriptions

```graphql
type Subscription {
  # Fires whenever any juror rates an image in the round
  imageRated(roundId: ID!): ImageRatedEvent!

  # Fires on each new rating with updated aggregate round progress
  roundProgress(roundId: ID!): RoundProgressEvent!
}

type ImageRatedEvent {
  roundId: ID!
  pageId: ID!
  juryId: ID!
  rate: Int!
}

type RoundProgressEvent {
  roundId: ID!
  ratedImages: Int!
  totalImages: Int!
  jurorStats: [JurorStat!]!
}
```

---

## Error Handling

All errors surface in the standard GraphQL `errors` array with an `extensions.code` field:

| Situation | `extensions.code` | HTTP status |
|---|---|---|
| No / invalid credential | `UNAUTHENTICATED` | 200 (GraphQL norm) |
| Insufficient role | `FORBIDDEN` | 200 |
| Entity not found | `NOT_FOUND` | 200 |
| Invalid input value | `BAD_INPUT` | 200 |
| Unhandled internal error | `INTERNAL_ERROR` | 200 |

A Sangria `ExceptionHandler` catches DB and unexpected exceptions and returns `INTERNAL_ERROR` without leaking stack traces in production (`play.mode = Prod`).

---

## Testing Strategy

| Layer | Approach |
|---|---|
| Schema validity | Unit test: parse SDL, build `SchemaDefinition`, assert no errors |
| Resolver tests | Extend `SharedTestDb` + Testcontainer MariaDB; call resolvers with a real `GraphQLContext` |
| Integration tests | Execute full GraphQL query strings via `sangria.execution.Executor`; assert response JSON |
| Auth tests | Inject `GraphQLContext(currentUser = None)` and unprivileged users; assert `UNAUTHENTICATED` / `FORBIDDEN` |
| Subscription tests | Use Sangria `SubscriptionStream` with `pekko.stream.testkit` |

Test files live in `test/graphql/` following the same `SharedTestDb` / `AutoRollbackMunitDb` patterns used in existing DB specs.

---

## File Layout

```
app/graphql/
  schema.graphql
  GraphQLContext.scala
  GraphQLRoute.scala          ← Pekko HTTP handler registered in ApiServer
  SchemaDefinition.scala
  resolvers/
    ContestResolver.scala
    RoundResolver.scala
    ImageResolver.scala
    UserResolver.scala
    CommentResolver.scala
    MonumentResolver.scala

test/graphql/
  SchemaValidationSpec.scala
  ContestResolverSpec.scala
  RoundResolverSpec.scala
  ImageResolverSpec.scala
  UserResolverSpec.scala
  CommentResolverSpec.scala
  MonumentResolverSpec.scala
  AuthSpec.scala
  SubscriptionSpec.scala
```
