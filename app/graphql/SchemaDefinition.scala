// app/graphql/SchemaDefinition.scala
package graphql

import graphql.resolvers._
import sangria.schema._

object SchemaDefinition {

  def schema(jwtSecret: String): Schema[GraphQLContext, Unit] = {

    val QueryType: ObjectType[GraphQLContext, Unit] = ObjectType(
      "Query",
      fields[GraphQLContext, Unit](
        ContestResolver.contestsField,
        ContestResolver.contestField,
        RoundResolver.roundField,
        RoundResolver.roundsField,
        RoundResolver.roundStatField,
        ImageResolver.imagesField,
        ImageResolver.imageField,
        UserResolver.usersField,
        UserResolver.userField,
        UserResolver.meField,
        CommentResolver.commentsField,
        MonumentResolver.monumentsField,
        MonumentResolver.monumentField
      )
    )

    val MutationType: ObjectType[GraphQLContext, Unit] = ObjectType(
      "Mutation",
      fields[GraphQLContext, Unit](
        AuthResolver.loginField(jwtSecret),
        ContestResolver.createContestField,
        ContestResolver.updateContestField,
        ContestResolver.deleteContestField,
        RoundResolver.createRoundField,
        RoundResolver.updateRoundField,
        RoundResolver.setActiveRoundField,
        RoundResolver.addJurorsToRoundField,
        ImageResolver.rateImageField,
        ImageResolver.rateImageBulkField,
        ImageResolver.setRoundImagesField,
        UserResolver.createUserField,
        UserResolver.updateUserField,
        CommentResolver.addCommentField
      )
    )

    val SubscriptionType: ObjectType[GraphQLContext, Unit] = ObjectType(
      "Subscription",
      fields[GraphQLContext, Unit](
        SubscriptionResolver.imageRatedField,
        SubscriptionResolver.roundProgressField
      )
    )

    Schema(QueryType, Some(MutationType), Some(SubscriptionType))
  }
}
