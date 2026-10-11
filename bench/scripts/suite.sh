# The measured suite (sourced): the same scenario for every code version, engine and config.
SUITE=(
  VotingSimulation
  JurorGallerySimulation
  RegionFilterSimulation
  RoundManagementSimulation
  "AggregatedRatingsSimulation@-Dgatling.aggRatings.withVoting=true"
  "RoundDistributionSimulation@-Dgatling.distribution.jurorsPerImage=2 -Dgatling.distribution.fromRated=true -Dgatling.distribution.topUp=true"
  "ConcurrentRoundCreationSimulation@-Dgatling.concurrent.jurorsPerImage=2"
)
