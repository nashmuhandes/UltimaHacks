package net.johnglassmyer.ultimahacks.ultimapatcher;

import static java.nio.ByteOrder.LITTLE_ENDIAN;
import static net.johnglassmyer.uncheckers.IoUncheckers.callUncheckedIoRunnable;
import static net.johnglassmyer.uncheckers.IoUncheckers.callUncheckedIoSupplier;
import static net.johnglassmyer.uncheckers.IoUncheckers.uncheckIoBiFunction;
import static net.johnglassmyer.uncheckers.IoUncheckers.uncheckIoFunction;
import static net.johnglassmyer.uncheckers.Uncheckers.uncheckFunction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import com.google.common.jimfs.Jimfs;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.PathConverter;
import joptsimple.util.PathProperties;
import net.johnglassmyer.ultimahacks.proto.HackProto;
import net.johnglassmyer.ultimahacks.ultimapatcher.Segment.Patchable;

/**
 * Applies patches to MS-DOS executables with overlays (originally, to Ultima VII's U7.EXE).
 * <p>
 * Adds patch-specified segment relocations to relocation tables and delists relocations previously
 * existing in patched bytes.
 */
public class UltimaPatcher {
	private static final int DEFAULT_EOP_SPACING = 0x100;

	static class Options {
		private static final PathConverter EXISTING_FILE_PATH_CONVERTER =
				new PathConverter(PathProperties.FILE_EXISTING);

		static Options parseFromCommandLine(String[] args) throws OptionException {
			OptionParser optionParser = new OptionParser();

			OptionSpec<Path> exe = optionParser.accepts("exe")
					.withRequiredArg()
					.withValuesConvertedBy(EXISTING_FILE_PATH_CONVERTER);

			OptionSpec<Void> listRelocations = optionParser.accepts("list-relocations")
					.availableIf(exe);

			OptionSpec<Void> showOverlayProcs = optionParser.accepts("show-overlay-procs")
					.availableIf(exe);

			OptionSpec<String> expandOverlay = optionParser.accepts("expand-overlay")
					.availableIf(exe)
					.withRequiredArg()
					.ofType(String.class);

			OptionSpec<String> eopSpacing = optionParser.accepts("eop-spacing")
					.availableIf(expandOverlay)
					.withRequiredArg()
					.ofType(String.class);

			OptionSpec<Void> ignoreExeLength = optionParser.accepts("ignore-exe-length")
					.availableIf(exe);

			OptionSpec<Path> patch = optionParser.accepts("patch")
					.requiredUnless(exe)
					.withRequiredArg()
					.withValuesConvertedBy(EXISTING_FILE_PATH_CONVERTER);

			OptionSpec<Void> showPatchBytes = optionParser.accepts("show-patch-bytes")
					.availableIf(patch);

			OptionSpec<Path> hackProto = optionParser.accepts("hack-proto")
					.availableIf(exe)
					.availableUnless(expandOverlay, patch)
					.withRequiredArg()
					.withValuesConvertedBy(EXISTING_FILE_PATH_CONVERTER);

			OptionSpec<Void> writeToExe = optionParser.accepts("write-to-exe")
					.availableIf(exe);

			OptionSpec<Path> writeHackProto = optionParser.accepts("write-hack-proto")
					.availableIf(exe)
					.availableUnless(hackProto, writeToExe)
					.withRequiredArg()
					.withValuesConvertedBy(new PathConverter());

			OptionSpec<String> hackComment = optionParser.accepts("hack-comment")
					.availableIf(writeHackProto)
					.withRequiredArg();

			OptionSpec<String> fileToSegmented = optionParser.accepts("file-to-segmented")
					.availableIf(exe)
					.availableUnless(listRelocations, patch, hackProto)
					.withRequiredArg();

			OptionSpec<String> segmentedToFile = optionParser.accepts("segmented-to-file")
					.availableIf(exe)
					.availableUnless(listRelocations, patch, hackProto, fileToSegmented)
					.withRequiredArg();

			OptionSpec<Void> produceSegmentsAsm = optionParser.accepts("produce-segments-asm")
					.availableIf(exe)
					.availableUnless(
							listRelocations, patch, hackProto, fileToSegmented, segmentedToFile);

			OptionSet optionSet = optionParser.parse(args);

			return new Options(
					optionSet.valueOfOptional(exe),
					optionSet.has(listRelocations),
					optionSet.has(showOverlayProcs),
					optionSet.has(ignoreExeLength),
					optionSet.valuesOf(expandOverlay),
					optionSet.valueOfOptional(eopSpacing),
					optionSet.valuesOf(patch),
					optionSet.has(showPatchBytes),
					optionSet.valueOfOptional(hackProto),
					optionSet.has(writeToExe),
					optionSet.valueOfOptional(writeHackProto),
					optionSet.valueOfOptional(hackComment),
					optionSet.valuesOf(fileToSegmented),
					optionSet.valuesOf(segmentedToFile),
					optionSet.has(produceSegmentsAsm));
		}

		final Optional<Path> exe;
		final boolean listRelocations;
		final boolean showOverlayProcs;
		final boolean ignoreExeLength;
		final List<String> expandOverlay;
		final Optional<String> eopSpacing;
		final List<Path> patch;
		final boolean showPatchBytes;
		final Optional<Path> hackProto;
		final boolean writeToExe;
		final Optional<Path> writeHackProto;
		final Optional<String> hackComment;
		final List<String> fileToSegmented;
		final List<String> segmentedToFile;
		final boolean produceSegmentsAsm;

		private Options(
				Optional<Path> exe,
				boolean listRelocations,
				boolean showOverlayProcs,
				boolean ignoreExeLength,
				List<String> expandOverlay,
				Optional<String> eopSpacing,
				List<Path> patch,
				boolean showPatchBytes,
				Optional<Path> hackProto,
				boolean writeToExe,
				Optional<Path> writeHackProto,
				Optional<String> hackComment,
				List<String> fileToSegmented,
				List<String> segmentedToFile,
				boolean produceSegmentsAsm) {
			this.exe = exe;
			this.listRelocations = listRelocations;
			this.showOverlayProcs = showOverlayProcs;
			this.ignoreExeLength = ignoreExeLength;
			this.expandOverlay = expandOverlay;
			this.eopSpacing = eopSpacing;
			this.patch = patch;
			this.showPatchBytes = showPatchBytes;
			this.hackProto = hackProto;
			this.writeToExe = writeToExe;
			this.writeHackProto = writeHackProto;
			this.hackComment = hackComment;
			this.fileToSegmented = fileToSegmented;
			this.segmentedToFile = segmentedToFile;
			this.produceSegmentsAsm = produceSegmentsAsm;
		}
	}

	enum Justification {
		LEFT, RIGHT;
	}

	private static final Logger L = LogManager.getLogger(UltimaPatcher.class);

	public static void main(String[] args) {
		// TODO: break this procedure up, make it shorter

		Options options;
		try {
			options = Options.parseFromCommandLine(args);
		} catch (OptionException e) {
			Stream.iterate(e, Objects::nonNull, Throwable::getCause)
					.forEach(System.err::println);

			logUsage();

			System.exit(-0xDEADBEEF);

			options = null;
		}

		main(options);
	}

	private static void main(Options options) {
		List<Patch> patches = options.patch.stream()
				.map(uncheckIoFunction(UltimaPatcher::readPatchFile))
				.collect(Collectors.toList());

		if (options.produceSegmentsAsm) {
			options.exe.map(uncheckIoFunction(Executable::readFromFile)).ifPresent(executable -> {
				executable.produceSegmentsAsm();
			});
		} else if (options.exe.isPresent()) {
			Path exePath = options.exe.get();

			ImmutableList.Builder<Edit> editsBuilder = ImmutableList.builder();

			int originalExeLength;
			Executable executable;
			{
				Executable originalExecutable =
						callUncheckedIoSupplier(() -> Executable.readFromFile(exePath));
				originalExecutable.logSummary();

				originalExeLength = originalExecutable.fileLength;

				ExecutableEditState expandedExecutableState = withExpandedOverlays(
						originalExecutable, options.expandOverlay, options.eopSpacing);
				executable = expandedExecutableState.executable;

				editsBuilder.addAll(expandedExecutableState.accumulatedEdits);
			}

			if (!patches.isEmpty()) {
				L.info(patches.size() + " patches:");
				for (Patch patch : patches) {
					patch.logDescription(options.showPatchBytes);

					checkTargetLength(
							patch.targetLength, executable.fileLength, options.ignoreExeLength);
				}

				editsBuilder.addAll(editsForPatches(executable, patches));
			}

			options.hackProto
					.map(uncheckIoFunction(Files::readAllBytes))
					.map(uncheckFunction(HackProto.Hack::parseFrom))
					.map(Hack::fromProtoHack)
					.ifPresent(hack -> {
				L.info("read hack proto");

				hack.targetLength.ifPresentOrElse(targetLength -> {
					L.info(String.format("  hack target file length: 0x%X", targetLength));
					checkTargetLength(targetLength, originalExeLength, options.ignoreExeLength);
				}, () -> {
					L.info("  hack does not specify a target file length");
				});

				hack.comment.ifPresentOrElse(comment -> {
					L.info("  hack comment: {}", comment);
				}, () -> {
					L.info("  has does not specify a comment");
				});

				editsBuilder.addAll(hack.edits);
			});

			ImmutableList<Edit> resultingEdits = editsBuilder.build();
			if (!resultingEdits.isEmpty()) {
				L.info("{} resulting edits:", resultingEdits.size());
				logMappedValues(Justification.LEFT, resultingEdits, Edit::explanation);

				if (options.writeToExe) {
					L.info("writing to exe {}", exePath);
					applyEdits(exePath, resultingEdits);
				} else if (options.writeHackProto.isPresent()) {
					Path hackPath = options.writeHackProto.get();
					L.info("writing hack proto to {}", hackPath);
					writeHackProto(
							hackPath, resultingEdits, originalExeLength, options.hackComment);
				} else {
					L.info("Use --write-to-exe to patch the executable"
							+ (options.hackProto.isPresent()
									? ""
									: " or --write-hack-proto to compile edits into a file")
							+ ".");
				}
			} else {
				if (!options.fileToSegmented.isEmpty()) {
					L.info("file offsets converted to segment:offset addresses:");
					logMappedValues(Justification.RIGHT, options.fileToSegmented, string -> {
						int fileOffset = Integer.decode(string);
						return executable.segmentIndexForFileOffset(fileOffset)
								.map(segmentIndex -> Util.formatAddress(
										segmentIndex,
										fileOffset - executable.segments.get(segmentIndex)
												.patchable().startInFile()))
								.or(() -> Optional.of("(no matching segment)"));
					});
				} else if (!options.segmentedToFile.isEmpty()) {
					L.info("segment:offset addresses converted to file offsets:");
					logMappedValues(Justification.RIGHT, options.segmentedToFile, string -> {
						SegmentAndOffset address = SegmentAndOffset.fromString(string);
						int segmentIndex = address.segmentIndex;
						if (0 <= segmentIndex  && segmentIndex < executable.segments.size()) {
							Patchable patchable = executable.segments.get(segmentIndex).patchable();
							int patchableZeroInFile =
									patchable.startInFile() - patchable.startOffset();
							if (patchable.startOffset() <= address.offset
									&& address.offset <= patchable.endOffset()) {
								return Optional.of(String.format(
										"0x%05X", patchableZeroInFile + address.offset));
							}
						}

						return Optional.of("(invalid address)");
					});
				} else if (options.listRelocations) {
					executable.listRelocations();
				} else {
					executable.logDetails(options.showOverlayProcs);
				}
			}
		} else if (!patches.isEmpty()) {
			L.info(patches.size() + " patches:");
			for (Patch patch : patches) {
				patch.logDescription(options.showPatchBytes);
			}
		}
	}

	private static void checkTargetLength(
			Integer targetLength, int fileLength, boolean ignoreExeLength) {
		if (targetLength != fileLength && !ignoreExeLength) {
			L.error(String.format(
					"Target file length 0x%X differs from executable length 0x%X."
					+ " Use --ignore-exe-length to bypass this check.",
					targetLength,
					fileLength));
			System.exit(0xDEADBEEF);
		}
	}

	private static void logUsage() {
		L.info("For executable info:");
		L.info("  java -jar UltimaPatcher.jar"
				+ " --exe=<exeFile> [--list-relocations | --show-overlay-procs]");
		L.info("For patch info:");
		L.info("  java -jar UltimaPatcher.jar --patch=<patchFile> [--show-patch-bytes]");
		L.info("To apply patches directly to an executable:");
		L.info("  java -jar UltimaPatcher.jar --exe=<exeFile>"
				+ " --expand-overlay=<segmentIndex>:<newLength>..."
				+ " --patch=<patchFile>..."
				+ " --write-to-exe");
		L.info("To compile patches to a hack proto:");
		L.info("  java -jar UltimaPatcher.jar --exe=<exeFile>"
				+ " --expand-overlay=<segmentIndex>:<newLength>..."
				+ " --patch=<patchFile>..."
				+ " --write-hack-proto=<hackProtoFile>");
		L.info("For compiled hack proto info:");
		L.info("  java -jar UltimaPatcher.jar --hack-proto=<hackProtoFile>");
		L.info("To apply a compiled hack proto to an executable:");
		L.info("  java -jar UltimaPatcher.jar --exe=<exeFile>"
				+ " --hack-proto=<hackProtoFile>"
				+ " --write-to-exe");
	}

	private static Patch readPatchFile(Path patchPath) throws IOException {
		byte[] patchFileBytes = Files.readAllBytes(patchPath);
		ByteBuffer buffer = ByteBuffer.wrap(patchFileBytes);
		buffer.order(LITTLE_ENDIAN);

		int offsetInPatch = buffer.capacity();

		offsetInPatch -= Integer.BYTES;
		int descriptionLength = buffer.getInt(offsetInPatch);

		offsetInPatch -= descriptionLength;
		byte[] descriptionBytes = new byte[descriptionLength];
		buffer.position(offsetInPatch);
		buffer.get(descriptionBytes);
		String description = new String(descriptionBytes, StandardCharsets.US_ASCII);

		offsetInPatch -= Integer.BYTES;
		int targetFileLength = buffer.getInt(offsetInPatch);

		offsetInPatch -= Integer.BYTES;
		int blockCount = buffer.getInt(offsetInPatch);

		List<PatchBlock> patchBlocks = new ArrayList<>(blockCount);
		for (int iBlock = 0; iBlock < blockCount; iBlock++) {
			offsetInPatch -= Integer.BYTES;
			int segmentIndex = buffer.getInt(offsetInPatch);

			offsetInPatch -= Integer.BYTES;
			int startWithinSegment = buffer.getInt(offsetInPatch);

			offsetInPatch -= Integer.BYTES;
			int relocationCount = buffer.getInt(offsetInPatch);

			List<Integer> relocationOffsets = new ArrayList<>(relocationCount);
			for (int iRelocation = 0; iRelocation < relocationCount; iRelocation++) {
				offsetInPatch -= Integer.BYTES;
				relocationOffsets.add(buffer.getInt(offsetInPatch));
			}

			offsetInPatch -= Integer.BYTES;
			int blockLength = buffer.getInt(offsetInPatch);

			byte[] codeBytes = new byte[blockLength];
			offsetInPatch -= blockLength;
			buffer.position(offsetInPatch);
			buffer.get(codeBytes);

			patchBlocks.add(new PatchBlock(
					segmentIndex, startWithinSegment, codeBytes, relocationOffsets));
		}

		return new Patch(description, targetFileLength, patchBlocks);
	}

	private static ExecutableEditState withExpandedOverlays(
			Executable executable, List<String> expandOverlayArgs, Optional<String> eopSpacingArg) {
		int eopSpacing = eopSpacingArg.map(Integer::decode).orElse(DEFAULT_EOP_SPACING);

		ExecutableEditOperation expandOverlaysOperation = expandOverlayArgs.stream()
				.map(SegmentAndOffset::fromString)
				.map(address -> (ExecutableEditOperation) new ExpandOverlayOperation(
						address.segmentIndex,
						address.offset,
						eopSpacing,
						uncheckIoBiFunction(UltimaPatcher::applyEditsInMemory)))
				.reduce(state -> state, (op1, op2) -> op1.andThen(op2));

		return expandOverlaysOperation.apply(ExecutableEditState.startingWith(executable));
	}

	private static Executable applyEditsInMemory(Executable executable, List<Edit> edits)
			throws IOException {
		// applying the edits to an in-memory file and then reading the edited executable
		// from scratch is hackish, but doing this is easier than re-writing the Executable class.

		byte[] exeBytes;
		try (FileChannel exeChannel = FileChannel.open(executable.path, StandardOpenOption.READ)) {
			exeBytes = Util.read(exeChannel, 0, (int) exeChannel.size());
		}

		FileSystem jimfs = Jimfs.newFileSystem();
		Path tempExePath = jimfs.getPath("temp.exe");
		try (FileChannel tempExeChannel = FileChannel.open(
				tempExePath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
			Util.write(tempExeChannel, 0, exeBytes);
		}

		applyEdits(tempExePath, edits);
		return Executable.readFromFile(tempExePath);
	}

	private static ImmutableList<Edit> editsForPatches(Executable executable, List<Patch> patches) {
		List<PatchBlock> blocks = patches.stream()
				.flatMap(p -> p.blocks.stream())
				.collect(Collectors.toList());

		List<PatchBlock> blocksBySegmentAndOffset = blocks.stream()
				.sorted(Comparator.comparing((PatchBlock b) -> b.segmentIndex)
						.thenComparing(b -> b.startOffset))
				.collect(Collectors.toList());

		Streams.forEachPair(
				blocksBySegmentAndOffset.stream(),
				blocksBySegmentAndOffset.stream().skip(1),
				(precedingBlock, block) -> {
			if (block.segmentIndex == precedingBlock.segmentIndex
					&& block.startOffset < precedingBlock.endOffset()) {
				throw new PatchApplicationException(String.format(
						"block for %s overlaps block for %s",
						block.formatAddress(),
						precedingBlock.formatAddress()));
			}
		});

		ImmutableList<Edit> edits; {
			ImmutableList.Builder<Edit> builder = ImmutableList.builder();

			RelocationTracker relocationTracker = RelocationTracker.forExecutable(executable);

			for (PatchBlock block : blocks) {
				Patchable patchable = Optional.of(executable.segments.get(block.segmentIndex))
						.map(Segment::patchable)
						.orElseThrow(() -> new PatchApplicationException(String.format(
							"no segment for block for %s", block.formatAddress())));

				if (block.startOffset < patchable.startOffset()
						|| block.endOffset() > patchable.endOffset()) {
					throw new PatchApplicationException(String.format(
							"block for %s is outside bounds of segment", block.formatAddress()));
				}

				for (Integer relocationWithinBlock : block.relocationsWithinBlock) {
					if (!(0 <= relocationWithinBlock
							&& relocationWithinBlock < block.codeBytes.length)) {
						throw new PatchApplicationException(String.format(
								"block for %s has out-of-range relocation offset 0x%X",
								block.formatAddress(),
								relocationWithinBlock));
					}
				}

				Set<Integer> relocationOffsets = block.relocationsWithinBlock.stream()
						.map(r -> block.startOffset + r)
						.collect(Collectors.toSet());

				relocationTracker.replaceInRange(
						block.segmentIndex,
						block.startOffset,
						block.endOffset(),
						relocationOffsets);

				builder.add(new OverwriteEdit(
						"patch block for " + block.formatAddress(),
						patchable.startInFile() + block.startOffset,
						block.codeBytes));
			}

			builder.addAll(relocationTracker.produceEdits());

			edits = builder.build();
		}

		return edits;
	}

	private static void applyEdits(Path filePath, Iterable<Edit> edits) {
		callUncheckedIoRunnable(() -> {
			try (SeekableByteChannel channel = FileChannel.open(
					filePath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
				for (Edit edit : edits) {
					edit.applyToFile(channel);
				}
			}
		});
	}

	private static void writeHackProto(
			Path path, ImmutableList<Edit> edits, int targetLength, Optional<String> comment) {
		Hack hack = new Hack(edits, Optional.of(targetLength), comment);
		callUncheckedIoRunnable(() -> Files.write(path, hack.toProtoHack().toByteArray()));
	}

	private static <T> void logMappedValues(
			Justification justification,
			Collection<T> values,
			Function<T, Optional<String>> mapper) {
		int maxStringLength = Util.maxStringLength(values.stream().map(Object::toString));
		String format =
				"%" + (justification == Justification.LEFT ? "-" : "") + maxStringLength + "s";

		values.forEach(value -> L.info(
				"  "
				+ String.format(format, value)
				+ mapper.apply(value).map(" => "::concat).orElse("")));
	}
}
